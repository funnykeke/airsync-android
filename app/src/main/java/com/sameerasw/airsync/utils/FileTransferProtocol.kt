package com.sameerasw.airsync.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.DataInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Existing wire fields, independent of Android/JSON so the production protocol runs in JVM tests. */
data class FileTransferFrame(
    val type: String, val id: String, val name: String = "", val size: Long = 0,
    val chunkSize: Int = 65536, val checksum: String = "", val targetPath: String? = null,
    val index: Int = -1, val bytes: ByteArray = byteArrayOf(), val verified: Boolean = false,
    val mime: String = "", val clipboardImage: Boolean = false
)

class FileTransferPolicy(
    val ackWaitMs: Long = 2000, val attempts: Int = 3,
    private val verificationBudgetMs: Long? = null, private val graceMs: Long? = null
) {
    fun verificationMs(size: Long): Long = verificationBudgetMs ?: (30_000.0 + size.toDouble() / 1048576 * 1000).toLong().coerceIn(60_000, 7_200_000)
    fun grace(): Long = graceMs ?: (ackWaitMs * (attempts + 2)).coerceIn(1000, 30_000)
}

/** One authenticated, captured transport owns this engine and all receipts/jobs/temp files. */
@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferProtocol(
    private val storageRoot: File,
    private val cacheDir: File,
    private val ownsSession: () -> Boolean,
    private val enqueue: (FileTransferFrame) -> Boolean,
    private val policy: FileTransferPolicy = FileTransferPolicy(),
    private val onSaved: (File) -> Unit = {},
    private val resolveSource: suspend (String) -> File = { SharedStoragePath.resolve(storageRoot, it) },
    private val beforeStage: suspend () -> Unit = {},
    private val onClipboardImage: (File) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var closed = false
    private data class Outgoing(val ack: Channel<Int>, val verified: CompletableDeferred<Boolean>, val job: Job,
        @Volatile var awaitingVerification: Boolean = false)
    private data class Incoming(val file: File, val handle: RandomAccessFile, val name: String,
        val directory: File, val layout: FileChunkLayout, val checksum: String, val clipboardImage: Boolean,
        val received: BitSet = BitSet(), var expiry: Long = 0, var fullyReceived: Boolean = false, var timer: Job? = null)
    private data class Receipt(val expires: Long)
    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incomingJobs = ConcurrentHashMap<String, MutableSet<Job>>()
    // Accessed only on the serial IO dispatcher, including close cleanup.
    private val incoming = mutableMapOf<String, Incoming>()
    private val receipts = mutableMapOf<String, Receipt>()
    private val receiverLock = Mutex()
    private fun now() = System.nanoTime() / 1_000_000
    private fun owns() = !closed && ownsSession()
    private suspend fun checkSession() { currentCoroutineContext().ensureActive(); check(owns()) { "Session changed" } }
    private fun send(frame: FileTransferFrame): Boolean = owns() && enqueue(frame)
    private fun cancelFrame(id: String) { send(FileTransferFrame("fileTransferCancel", id)) }

    fun pull(path: String, requestId: String? = null): Boolean {
        val id = requestId ?: UUID.randomUUID().toString()
        if (!validId(id) || !owns()) return false
        val ack = Channel<Int>(64)
        val verified = CompletableDeferred<Boolean>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { sendFile(path, id, ack, verified) }
            catch (_: CancellationException) { /* Remote cancel/close already invalidates this job. */ }
            catch (_: Exception) { cancelFrame(id) }
        }
        val task = Outgoing(ack, verified, job)
        if (outgoing.putIfAbsent(id, task) != null) { job.cancel(); ack.close(); return false }
        job.invokeOnCompletion { outgoing.remove(id, task); ack.close(); verified.cancel() }
        job.start()
        return true
    }

    fun handle(frame: FileTransferFrame) {
        if (!validId(frame.id) || !owns()) return
        when (frame.type) {
            "fileChunkAck" -> { outgoing[frame.id]?.ack?.trySend(frame.index); return }
            "transferVerified" -> { outgoing[frame.id]?.takeIf { it.awaitingVerification }?.verified?.complete(frame.verified); return }
            "fileTransferCancel" -> {
                outgoing[frame.id]?.job?.cancel()
                incomingJobs.remove(frame.id)?.forEach { it.cancel() }
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { receiverLock.withLock {
            if (!owns()) return@launch
            receipts.entries.removeAll { it.value.expires <= now() }
            try {
                when (frame.type) {
                    "fileTransferInit" -> init(frame)
                    "fileChunk" -> chunk(frame)
                    "fileTransferComplete" -> complete(frame)
                    "fileTransferCancel" -> { discard(frame.id); receipts.remove(frame.id) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { discard(frame.id); cancelFrame(frame.id) }
        } }
        if (frame.type != "fileTransferCancel") {
            incomingJobs.compute(frame.id) { _, jobs -> (jobs ?: mutableSetOf()).apply { add(job) } }
            job.invokeOnCompletion {
                incomingJobs.computeIfPresent(frame.id) { _, jobs -> jobs.apply { remove(job) }.takeIf { it.isNotEmpty() } }
            }
        }
        job.start()
    }

    private fun init(frame: FileTransferFrame) {
        require(!incoming.containsKey(frame.id) && !receipts.containsKey(frame.id) && incoming.size < 3)
        require(frame.name.isNotBlank() && frame.name !in setOf(".", "..") && frame.name.none { it == '/' || it == '\\' || it == '\u0000' })
        require(frame.checksum.matches(Regex("[A-Fa-f0-9]{64}")))
        if (frame.clipboardImage) {
            require(frame.mime == "image/png" && frame.name.endsWith(".png", ignoreCase = true))
            require(frame.size in pngSignature.size.toLong()..32L * 1024 * 1024)
        }
        val layout = FileChunkLayout(frame.size, frame.chunkSize)
        val directory = FileTransferDestination.resolve(storageRoot, frame.targetPath)
        require(cacheDir.usableSpace > layout.size)
        val file = File.createTempFile("airsync-incoming-", ".part", cacheDir)
        val transfer = Incoming(file, RandomAccessFile(file, "rw"), frame.name, directory, layout,
            frame.checksum, frame.clipboardImage)
        incoming[frame.id] = transfer
        renew(frame.id, transfer)
    }
    private fun chunk(frame: FileTransferFrame) {
        val transfer = incoming[frame.id] ?: error("Unknown transfer")
        require(frame.bytes.size == transfer.layout.length(frame.index))
        check(owns())
        // A duplicate is acknowledged but never overwrites already validated data.
        if (!transfer.received[frame.index]) {
            transfer.handle.seek(transfer.layout.offset(frame.index)); transfer.handle.write(frame.bytes)
            transfer.received.set(frame.index)
        }
        renew(frame.id, transfer)
        check(send(FileTransferFrame("fileChunkAck", frame.id, index = frame.index)))
    }
    private fun renew(id: String, transfer: Incoming) {
        val full = transfer.received.cardinality() == transfer.layout.count
        if (!transfer.fullyReceived) {
            transfer.expiry = now() + if (full) policy.verificationMs(transfer.layout.size) + policy.grace() else policy.ackWaitMs * (policy.attempts + 2)
            transfer.fullyReceived = full
        }
        transfer.timer?.cancel()
        transfer.timer = scope.launch {
            delay((transfer.expiry - now()).coerceAtLeast(1))
            receiverLock.withLock { if (incoming[id] === transfer) { discard(id); cancelFrame(id) } }
        }
    }
    private suspend fun complete(frame: FileTransferFrame) {
        if (receipts[frame.id]?.let { it.expires > now() } == true) {
            send(FileTransferFrame("transferVerified", frame.id, verified = true)); return
        }
        val transfer = incoming[frame.id] ?: error("Unknown transfer")
        require(transfer.fullyReceived && transfer.file.length() == transfer.layout.size)
        transfer.timer?.cancel()
        transfer.handle.close()
        try {
            require(sha256(transfer.file).equals(transfer.checksum, true))
            if (transfer.clipboardImage) {
                val header = ByteArray(pngSignature.size)
                DataInputStream(transfer.file.inputStream()).use { it.readFully(header) }
                require(header.contentEquals(pngSignature))
            }
            beforeStage()
            checkSession()
            // Re-resolve after hashing/delay so a changed symlink cannot escape the root.
            val directory = SharedStoragePath.resolve(storageRoot, transfer.directory.path)
            directory.mkdirs()
            require(directory.usableSpace > transfer.layout.size)
            var target = File(directory, transfer.name)
            var counter = 1
            while (!target.createNewFile()) {
                checkSession()
                val original = File(transfer.name)
                target = File(directory, "${original.nameWithoutExtension} (${counter++})" + original.extension.let { if (it.isEmpty()) "" else ".$it" })
            }
            try {
                transfer.file.inputStream().use { input -> target.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) { checkSession(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                } }
                checkSession()
                if (transfer.clipboardImage) onClipboardImage(target)
                if (receipts.size >= 32) receipts.minByOrNull { it.value.expires }?.key?.let { receipts.remove(it) }
                receipts[frame.id] = Receipt(now() + policy.verificationMs(transfer.layout.size) + policy.grace())
                send(FileTransferFrame("transferVerified", frame.id, verified = true))
                if (!transfer.clipboardImage) onSaved(target)
            } catch (error: Exception) { target.delete(); throw error }
        } finally { discard(frame.id) }
    }
    private fun discard(id: String) {
        incoming.remove(id)?.let { it.timer?.cancel(); runCatching { it.handle.close() }; it.file.delete() }
    }

    private suspend fun sendFile(path: String, id: String, ack: Channel<Int>, verified: CompletableDeferred<Boolean>) {
        val file = resolveSource(path)
        checkSession()
        require(file.isFile && file.canRead())
        val layout = FileChunkLayout(file.length(), 65536)
        val checksum = sha256(file)
        checkSession()
        check(send(FileTransferFrame("fileTransferInit", id, file.name, layout.size, layout.chunkSize, checksum)))
        file.inputStream().use { input ->
            var first = 0
            while (first < layout.count) {
                val window = (first until minOf(first + 8, layout.count)).associateWith { index ->
                    val bytes = ByteArray(layout.length(index))
                    var read = 0
                    while (read < bytes.size) { checkSession(); val n = input.read(bytes, read, bytes.size - read); check(n > 0); read += n }
                    FileTransferFrame("fileChunk", id, index = index, bytes = bytes)
                }
                val pending = window.keys.toMutableSet()
                for (attempt in 0 until policy.attempts) {
                    for (index in pending) { checkSession(); check(send(window.getValue(index))) }
                    val deadline = now() + policy.ackWaitMs
                    while (pending.isNotEmpty() && now() < deadline) {
                        checkSession()
                        withTimeoutOrNull(minOf(50, (deadline - now()).coerceAtLeast(1))) { ack.receive() }?.let { pending.remove(it) }
                    }
                    if (pending.isEmpty()) break
                }
                check(pending.isEmpty()) { "Chunk timeout" }
                first += window.size
            }
        }
        outgoing[id]?.awaitingVerification = true
        val budget = policy.verificationMs(layout.size)
        val start = now()
        for (attempt in 0 until policy.attempts) {
            checkSession()
            check(send(FileTransferFrame("fileTransferComplete", id, file.name, layout.size, checksum = checksum)))
            val deadline = start + budget * (attempt + 1) / policy.attempts
            while (now() < deadline) {
                checkSession()
                val result = withTimeoutOrNull(minOf(50, (deadline - now()).coerceAtLeast(1))) { verified.await() }
                if (result != null) { check(result) { "Verification failed" }; return }
            }
        }
        error("Verification timeout")
    }
    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            checkSession(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun close() {
        closed = true
        scope.coroutineContext.cancelChildren()
        outgoing.values.forEach { it.job.cancel() }
        scope.launch { receiverLock.withLock { incoming.keys.toList().forEach(::discard); receipts.clear() } }
    }
    companion object {
        private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        fun validId(id: String) = id.matches(Regex("[A-Za-z0-9-]{1,80}"))
    }
}
