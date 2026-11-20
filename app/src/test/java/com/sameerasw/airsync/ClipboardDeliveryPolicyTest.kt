package com.sameerasw.airsync

import com.sameerasw.airsync.utils.ClipboardDeliveryPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ClipboardDeliveryPolicyTest {
    @Test fun copyingEarlierValueAgainSupersedesQueuedDifferentValue() {
        for (remote in listOf(false, true)) {
            val policy = ClipboardDeliveryPolicy()
            if (remote) policy.received("A") else policy.complete(policy.candidate("A")!!, true)
            val queued = policy.candidate("B")!!
            val last = policy.candidate("A")
            assertNotNull(last)
            assertFalse(policy.canDeliver(queued))
            assertTrue(policy.canDeliver(last!!))
        }
    }

    @Test fun newerClipboardAndRemoteEventsInvalidateQueuedDelivery() {
        val policy = ClipboardDeliveryPolicy()
        val first = policy.candidate("first")!!
        assertTrue(policy.canDeliver(first))
        val second = policy.candidate("second")!!
        assertFalse(policy.canDeliver(first))
        assertTrue(policy.canDeliver(second))
        policy.received("remote")
        assertFalse(policy.canDeliver(second))
        val third = policy.candidate("third")!!
        policy.reset()
        assertFalse(policy.canDeliver(third))
    }

    @Test fun rejectsBlankSensitiveAndOversizeUtf8() {
        val policy = ClipboardDeliveryPolicy(4)
        assertNull(policy.candidate(" \n\t"))
        assertNull(policy.candidate("safe", sensitive = true))
        assertNull(policy.candidate("你好"))
        assertNotNull(policy.candidate("😀"))
    }

    @Test fun suppressesPendingAndSuccessfulDuplicateButRetriesFailure() {
        val policy = ClipboardDeliveryPolicy()
        val first = policy.candidate("first")!!
        assertNull(policy.candidate("first"))
        policy.complete(first, false)
        val retry = policy.candidate("first")!!
        assertNotEquals(first, retry)
        policy.complete(retry, true)
        assertNull(policy.candidate("first"))
        assertNotNull(policy.candidate("second"))
    }

    @Test fun staleCompletionDoesNotReplaceNewerSuccess() {
        val policy = ClipboardDeliveryPolicy()
        val old = policy.candidate("old")!!
        val newer = policy.candidate("new")!!
        policy.complete(newer, true)
        policy.complete(old, true)
        assertNull(policy.candidate("new"))
        assertNotNull(policy.candidate("old"))
    }

    @Test fun newerFailureNeverRestoresSupersededDelivery() {
        val policy = ClipboardDeliveryPolicy()
        val old = policy.candidate("old")!!
        val newer = policy.candidate("new")!!
        policy.complete(newer, false)
        assertFalse(policy.canDeliver(old))
        val retry = policy.candidate("new")!!
        assertTrue(policy.canDeliver(retry))
        policy.complete(old, false)
        assertTrue(policy.canDeliver(retry))
    }

    @Test fun receivedTextSuppressesEchoAndSupersedesOlderCompletion() {
        val policy = ClipboardDeliveryPolicy()
        val old = policy.candidate("old")!!
        policy.received("remote")
        assertNull(policy.candidate("remote"))
        policy.complete(old, true)
        assertNotNull(policy.candidate("old"))
        policy.received("new remote")
        assertNotNull(policy.candidate("remote"))
    }

    @Test fun resetInvalidatesOldCompletionAndClearsSuppression() {
        val policy = ClipboardDeliveryPolicy()
        val old = policy.candidate("local")!!
        policy.received("remote")
        policy.reset()
        val current = policy.candidate("local")!!
        assertNotEquals(old, current)
        policy.complete(old, true)
        policy.complete(current, false)
        assertNotNull(policy.candidate("local"))
        assertNotNull(policy.candidate("remote"))
    }

    @Test fun completedTokenCannotBeAppliedTwice() {
        val policy = ClipboardDeliveryPolicy()
        val token = policy.candidate("value")!!
        policy.complete(token, false)
        policy.complete(token, true)
        assertNotNull(policy.candidate("value"))
    }

    @Test fun concurrentDuplicateCandidatesReserveExactlyOnce() {
        val policy = ClipboardDeliveryPolicy()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((1..64).map { Callable { policy.candidate("same") } })
            assertEquals(1, results.count { it.get() != null })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun rapidCopiesAlwaysKeepLatestInsteadOfDroppingAtCapacity() {
        val policy = ClipboardDeliveryPolicy()
        val tokens = (1..100).mapNotNull { policy.candidate("value-$it") }
        assertEquals(100, tokens.size)
        assertTrue(tokens.dropLast(1).none { policy.canDeliver(it) })
        assertTrue(policy.canDeliver(tokens.last()))
        policy.candidate("password", sensitive = true)
        assertFalse(policy.canDeliver(tokens.last()))
    }
}
