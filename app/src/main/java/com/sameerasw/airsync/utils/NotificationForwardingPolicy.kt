package com.sameerasw.airsync.utils

/** Classification is per notification; an app's other notifications remain eligible. */
object NotificationForwardingPolicy {
    fun isPlayback(category: String?, hasSessionExtra: Boolean): Boolean =
        category == "transport" || hasSessionExtra
}
