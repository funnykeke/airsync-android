package com.sameerasw.airsync

import com.sameerasw.airsync.utils.FileBrowseRequests
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FileBrowseRequestsTest {
    @Test fun slowOldListingCannotReplaceNewNavigation() = runBlocking {
        val browse = FileBrowseRequests()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<String>()
        try {
            browse.request({ true }, { entered.countDown(); release.await(2, TimeUnit.SECONDS); "old" }, { sent.add(it); true })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            browse.request({ true }, { "new" }, { sent.add(it); true })
            withTimeout(1000) { while (sent.isEmpty()) delay(5) }
            release.countDown(); delay(80)
            assertEquals(listOf("new"), sent.toList())
        } finally { release.countDown(); browse.reset() }
    }
    @Test fun delayedListingStaysBoundToOriginalSessionAcrossReset() = runBlocking {
        val browse = FileBrowseRequests()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sent = CopyOnWriteArrayList<String>()
        var originalValid = true
        try {
            browse.request({ originalValid }, { entered.countDown(); release.await(2, TimeUnit.SECONDS); "secret" }, { sent.add(it); true })
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            originalValid = false
            browse.reset()
            browse.request({ true }, { "replacement" }, { sent.add(it); true })
            release.countDown()
            withTimeout(1000) { while (sent.isEmpty()) delay(5) }
            delay(80)
            assertEquals(listOf("replacement"), sent.toList())
        } finally { release.countDown(); browse.reset() }
    }
}
