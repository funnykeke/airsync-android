package com.sameerasw.airsync

import com.sameerasw.airsync.utils.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class FileTransferProtocolTest {
    private class Pairing(val drop: (FileTransferFrame) -> Boolean = { false }) : AutoCloseable {
        val root = Files.createTempDirectory("protocol").toFile()
        val source = File(root, "source.bin").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val saved = CopyOnWriteArrayList<File>()
        val frames = CopyOnWriteArrayList<FileTransferFrame>()
        var live = true
        val sender: FileTransferProtocol
        val receiver = FileTransferProtocol(root, File(root, "rcache").apply { mkdir() }, { live }, { frame ->
            frames.add(frame); if (!drop(frame)) sender.handle(frame); true
        }, FileTransferPolicy(ackWaitMs = 30, verificationBudgetMs = 1200, graceMs = 80), onSaved = { saved.add(it) })
        init {
            sender = FileTransferProtocol(root, File(root, "scache").apply { mkdir() }, { live }, { frame ->
                frames.add(frame); if (!drop(frame)) receiver.handle(frame); true
            }, FileTransferPolicy(ackWaitMs = 30, verificationBudgetMs = 1200, graceMs = 80))
        }
        override fun close() { sender.close(); receiver.close(); root.deleteRecursively() }
    }
    private suspend fun until(predicate: () -> Boolean) { withTimeout(4000) { while (!predicate()) delay(5) } }

    @Test fun actualProtocolUsesRequestedIdAndWaitsForVerifiedSave() = runBlocking {
        Pairing().use { p ->
            p.sender.pull(p.source.path, "approved-download")
            until { p.frames.any { it.type == "transferVerified" } }
            assertEquals(1, p.saved.size)
            assertArrayEquals(p.source.readBytes(), p.saved.single().readBytes())
            assertTrue(p.frames.all { it.id == "approved-download" })
            assertFalse(p.frames.any { it.type == "fileTransferCancel" })
        }
    }
    @Test fun lostFirstCompleteSurvivesShortChunkIdleLifetime() = runBlocking {
        var completes = 0
        Pairing { it.type == "fileTransferComplete" && ++completes == 1 }.use { p ->
            p.sender.pull(p.source.path, "lost-complete")
            until { p.saved.isNotEmpty() }
            assertEquals(2, completes)
            assertArrayEquals(p.source.readBytes(), p.saved.single().readBytes())
            assertFalse(p.frames.any { it.type == "fileTransferCancel" })
        }
    }
    @Test fun lostPositiveVerifySurvivesInterveningCompletionWithoutDuplicateSave() = runBlocking {
        var dropped = false
        Pairing { it.type == "transferVerified" && it.id == "first" && !dropped.also { dropped = true } }.use { p ->
            p.sender.pull(p.source.path, "first")
            until { p.saved.size == 1 }
            delay(180)
            p.sender.pull(p.source.path, "second")
            until { p.saved.size == 2 && p.frames.count { it.type == "transferVerified" && it.id == "first" } == 2 }
            assertEquals(2, p.saved.size)
            assertFalse(p.frames.any { it.type == "fileTransferCancel" })
        }
    }
    @Test fun missingVerificationHasOneBoundedBudgetAndThreeCompletes() = runBlocking {
        Pairing { it.type == "transferVerified" }.use { p ->
            p.sender.pull(p.source.path, "no-verify")
            until { p.frames.any { it.type == "fileTransferCancel" } }
            assertEquals(3, p.frames.count { it.type == "fileTransferComplete" })
        }
    }
    @Test fun failureUsesSuppliedIdAndLegacyRequestGeneratesSafeUuid() = runBlocking {
        Pairing().use { p ->
            p.sender.pull(File(p.root, "missing").path, "failed-download")
            until { p.frames.any { it.type == "fileTransferCancel" && it.id == "failed-download" } }
            p.sender.pull(p.source.path)
            until { p.saved.isNotEmpty() }
            val id = p.frames.first { it.type == "fileTransferInit" }.id
            assertTrue(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
            assertNotEquals("failed-download", id)
        }
    }
    @Test fun cancellationDuringQueuedSourceResolutionAndDuplicateIdNeverReplaceJob() = runBlocking {
        val root = Files.createTempDirectory("cancel").toFile()
        val gate = CompletableDeferred<Unit>()
        val frames = CopyOnWriteArrayList<FileTransferFrame>()
        val engine = FileTransferProtocol(root, root, { true }, { frames.add(it); true }, resolveSource = { gate.await(); File(root, "file") })
        try {
            assertTrue(engine.pull("file", "queued"))
            assertFalse(engine.pull("file", "queued"))
            engine.handle(FileTransferFrame("fileTransferCancel", "queued"))
            gate.complete(Unit)
            delay(80)
            assertFalse(frames.any { it.type == "fileTransferInit" })
        } finally { engine.close(); root.deleteRecursively() }
    }
    @Test fun negativeVerificationAndSessionInvalidationStopPromptly() = runBlocking {
        Pairing { it.type == "transferVerified" }.use { p ->
            p.sender.pull(p.source.path, "negative")
            until { p.saved.isNotEmpty() }
            p.sender.handle(FileTransferFrame("transferVerified", "negative", verified = false))
            withTimeout(200) { until { p.frames.any { it.type == "fileTransferCancel" } } }
        }
        Pairing { it.type == "transferVerified" }.use { p ->
            p.sender.pull(p.source.path, "stale")
            until { p.saved.isNotEmpty() }
            p.live = false
            val count = p.frames.size
            delay(450)
            assertEquals(count, p.frames.size)
        }
    }
    @Test fun duplicateCompleteDuringSlowStagingNeverSavesTwice() = runBlocking {
        val root = Files.createTempDirectory("slow-stage").toFile()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = CopyOnWriteArrayList<File>()
        val frames = CopyOnWriteArrayList<FileTransferFrame>()
        val engine = FileTransferProtocol(root, root, { true }, { frames.add(it); true },
            beforeStage = { entered.complete(Unit); release.await() }, onSaved = { saved.add(it) })
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            engine.handle(FileTransferFrame("fileTransferInit", "slow", "saved.bin", 3, checksum = checksum))
            engine.handle(FileTransferFrame("fileChunk", "slow", index = 0, bytes = bytes))
            engine.handle(FileTransferFrame("fileTransferComplete", "slow"))
            withTimeout(1000) { entered.await() }
            engine.handle(FileTransferFrame("fileTransferComplete", "slow"))
            delay(80)
            release.complete(Unit)
            until { frames.count { it.type == "transferVerified" } == 2 }
            assertEquals(1, saved.size)
            assertFalse(frames.any { it.type == "fileTransferCancel" })
        } finally { engine.close(); delay(30); root.deleteRecursively() }
    }
    @Test fun receiverEnforcesChecksumAndDeletesPartialInputAfterIdle() = runBlocking {
        val root = Files.createTempDirectory("bad-input").toFile()
        val cache = File(root, "cache").apply { mkdir() }
        val frames = CopyOnWriteArrayList<FileTransferFrame>()
        val engine = FileTransferProtocol(root, cache, { true }, { frames.add(it); true }, FileTransferPolicy(ackWaitMs = 20))
        try {
            engine.handle(FileTransferFrame("fileTransferInit", "bad", "bad.bin", 3, checksum = "0".repeat(64)))
            engine.handle(FileTransferFrame("fileChunk", "bad", index = 0, bytes = byteArrayOf(1, 2, 3)))
            engine.handle(FileTransferFrame("fileTransferComplete", "bad"))
            until { frames.any { it.type == "fileTransferCancel" && it.id == "bad" } }
            assertFalse(File(root, "bad.bin").exists())
            engine.handle(FileTransferFrame("fileTransferInit", "partial", "partial.bin", 100_000, checksum = "0".repeat(64)))
            until { frames.any { it.type == "fileTransferCancel" && it.id == "partial" } }
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally { engine.close(); delay(30); root.deleteRecursively() }
    }
    @Test fun cancelledJobBeforeIoDispatchReleasesRequestId() = runBlocking {
        val root = Files.createTempDirectory("queued-id").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val engine = FileTransferProtocol(root, root, { true }, { true }, resolveSource = {
            if (it == "block") { entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            File(root, "missing")
        })
        try {
            engine.pull("block", "blocking")
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(engine.pull("queued", "queued"))
            engine.handle(FileTransferFrame("fileTransferCancel", "queued"))
            release.countDown()
            delay(100)
            assertTrue("Cancelled queued id must be reusable", engine.pull("queued", "queued"))
        } finally { release.countDown(); engine.close(); delay(30); root.deleteRecursively() }
    }
    @Test fun remoteCancelInterruptsIncomingStagingBeforeSave() = runBlocking {
        val root = Files.createTempDirectory("cancel-stage").toFile()
        val cache = File(root, "cache").apply { mkdir() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val frames = CopyOnWriteArrayList<FileTransferFrame>()
        val engine = FileTransferProtocol(root, cache, { true }, { frames.add(it); true },
            beforeStage = { entered.complete(Unit); release.await() })
        try {
            val bytes = byteArrayOf(1)
            val checksum = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            engine.handle(FileTransferFrame("fileTransferInit", "cancel", "saved.bin", 1, checksum = checksum, targetPath = root.path))
            engine.handle(FileTransferFrame("fileChunk", "cancel", index = 0, bytes = bytes))
            engine.handle(FileTransferFrame("fileTransferComplete", "cancel"))
            withTimeout(1000) { entered.await() }
            engine.handle(FileTransferFrame("fileTransferCancel", "cancel"))
            delay(30)
            release.complete(Unit)
            until { cache.listFiles()!!.isEmpty() }
            assertFalse(File(root, "saved.bin").exists())
            assertFalse(frames.any { it.type == "transferVerified" })
        } finally { engine.close(); delay(30); root.deleteRecursively() }
    }
    @Test fun verificationBeforeCompleteCannotReportFalseSuccess() = runBlocking {
        val root = Files.createTempDirectory("early-verify").toFile()
        val source = File(root, "source").apply { writeBytes(byteArrayOf(1)) }
        val frames = CopyOnWriteArrayList<FileTransferFrame>()
        lateinit var engine: FileTransferProtocol
        engine = FileTransferProtocol(root, root, { true }, { frame ->
            frames.add(frame)
            if (frame.type == "fileTransferInit") engine.handle(FileTransferFrame("transferVerified", frame.id, verified = true))
            if (frame.type == "fileChunk") engine.handle(FileTransferFrame("fileChunkAck", frame.id, index = frame.index))
            true
        }, FileTransferPolicy(ackWaitMs = 30, verificationBudgetMs = 180))
        try {
            engine.pull(source.path, "early")
            withTimeout(1000) { until { frames.any { it.type == "fileTransferCancel" } } }
            assertEquals(3, frames.count { it.type == "fileTransferComplete" })
        } finally { engine.close(); delay(30); root.deleteRecursively() }
    }
    @Test fun budgetsMatchMacPolicy() {
        val policy = FileTransferPolicy()
        assertEquals(60_000, policy.verificationMs(0))
        assertEquals(1_054_000, policy.verificationMs(1L shl 30))
        assertEquals(7_200_000, policy.verificationMs(1L shl 40))
        assertEquals(10_000, policy.grace())
    }
}
