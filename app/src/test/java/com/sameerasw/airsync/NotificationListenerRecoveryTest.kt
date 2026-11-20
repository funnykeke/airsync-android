package com.sameerasw.airsync

import com.sameerasw.airsync.utils.NotificationListenerRecovery
import org.junit.Assert.*
import org.junit.Test

class NotificationListenerRecoveryTest {
    @Test fun grantedButUnboundListenerIsRecoveredWithoutTogglingPermission() {
        assertTrue(NotificationListenerRecovery().shouldRequestRebind(true, false, 0L))
    }

    @Test fun neverRequestsBindingWithoutUserPermission() {
        val recovery = NotificationListenerRecovery()
        assertFalse(recovery.shouldRequestRebind(false, false, 0L))
        assertTrue(recovery.shouldRequestRebind(true, false, 1L))
    }

    @Test fun healthyListenerIsNotInterrupted() {
        val recovery = NotificationListenerRecovery()
        assertFalse(recovery.shouldRequestRebind(true, true, 0L))
        assertTrue(recovery.shouldRequestRebind(true, false, 1L))
    }

    @Test fun repeatedForegroundEventsDoNotFloodSystemButFailedBindingRetries() {
        val recovery = NotificationListenerRecovery()
        assertTrue(recovery.shouldRequestRebind(true, false, 0L))
        assertFalse(recovery.shouldRequestRebind(true, false, 1L))
        assertFalse(recovery.shouldRequestRebind(true, false, 29_999L))
        assertTrue(recovery.shouldRequestRebind(true, false, 30_000L))
    }
}
