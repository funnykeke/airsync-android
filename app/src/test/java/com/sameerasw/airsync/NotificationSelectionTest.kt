package com.sameerasw.airsync

import com.sameerasw.airsync.domain.model.NotificationApp
import com.sameerasw.airsync.utils.AppUtil
import org.junit.Assert.*
import org.junit.Test

class NotificationSelectionTest {
    @Test fun newlyDiscoveredAppDoesNotOptItselfIntoNotificationSync() {
        assertFalse(NotificationApp("new.app", "New app").isEnabled)
    }

    @Test fun refreshingAppInventoryDoesNotEnableNewApps() {
        val installed = listOf(NotificationApp("new.app", "New app", isEnabled = true))
        assertFalse(AppUtil.mergeWithSavedApps(installed, emptyList()).single().isEnabled)
    }

    @Test fun refreshingInventoryPreservesExplicitWechatSelection() {
        val installed = listOf(NotificationApp("com.tencent.mm", "微信"), NotificationApp("other.app", "Other"))
        val saved = listOf(NotificationApp("com.tencent.mm", "微信", isEnabled = true), NotificationApp("other.app", "Other", isEnabled = false))
        val merged = AppUtil.mergeWithSavedApps(installed, saved).associateBy { it.packageName }
        assertTrue(merged.getValue("com.tencent.mm").isEnabled)
        assertFalse(merged.getValue("other.app").isEnabled)
    }
}
