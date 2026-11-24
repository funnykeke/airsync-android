package com.sameerasw.airsync

import com.sameerasw.airsync.utils.NotificationForwardingPolicy
import org.junit.Assert.*
import org.junit.Test

class NotificationForwardingPolicyTest {
    @Test fun transportCategoryIsExcludedEvenWithoutSessionExtras() {
        assertTrue(NotificationForwardingPolicy.isPlayback("transport", false))
    }

    @Test fun sessionExtrasExcludePlaybackEvenWhenCategoryIsMissingOrGeneric() {
        assertTrue(NotificationForwardingPolicy.isPlayback(null, true))
        assertTrue(NotificationForwardingPolicy.isPlayback("status", true))
    }

    @Test fun ordinaryNotificationsFromTheSameAppRemainEligible() {
        data class Entry(val app: String, val category: String?, val hasSession: Boolean, val id: String)
        val entries = listOf(
            Entry("example.music", "transport", true, "player"),
            Entry("example.music", "message", false, "message"),
            Entry("example.music", null, false, "download")
        )
        assertEquals(listOf("message", "download"), entries.filterNot {
            NotificationForwardingPolicy.isPlayback(it.category, it.hasSession)
        }.map { it.id })
    }

    @Test fun removalNeedsAForwardedIdAndCurrentConsent() {
        val policy = NotificationForwardingPolicy
        assertEquals("forwarded-id", policy.dismissalId("forwarded-id", eligible = true, suppressed = false))
        assertNull(policy.dismissalId(null, eligible = true, suppressed = false))
        assertNull(policy.dismissalId("forwarded-id", eligible = false, suppressed = false))
        assertNull(policy.dismissalId("forwarded-id", eligible = true, suppressed = true))
    }

    @Test fun globalSyncAndExplicitAppSelectionAreBothRequired() {
        val policy = NotificationForwardingPolicy
        assertTrue(policy.isSelected(syncEnabled = true, appEnabled = true))
        assertFalse(policy.isSelected(syncEnabled = true, appEnabled = null))
        assertFalse(policy.isSelected(syncEnabled = true, appEnabled = false))
        assertFalse(policy.isSelected(syncEnabled = false, appEnabled = true))
    }
}
