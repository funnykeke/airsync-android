package com.sameerasw.airsync.utils

/** Decides when to retry a granted notification listener that is not bound. */
class NotificationListenerRecovery {
    private var lastRequestMs: Long? = null

    @Synchronized
    fun shouldRequestRebind(permissionGranted: Boolean, bound: Boolean, nowMs: Long): Boolean {
        if (!permissionGranted || bound) return false
        val previous = lastRequestMs
        if (previous != null && nowMs - previous < 30_000L) return false
        lastRequestMs = nowMs
        return true
    }
}
