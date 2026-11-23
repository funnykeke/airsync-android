package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.NotificationDismissalUtil
import com.sameerasw.airsync.utils.NotificationListenerRecovery
import com.sameerasw.airsync.utils.NotificationForwardingPolicy
import com.sameerasw.airsync.utils.PermissionUtil
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.LinkedList

/** Historical component name preserves existing notification access grants after upgrading. */
class MediaNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var serviceInstance: MediaNotificationListener? = null
        private const val TAG = "MediaNotificationListener"

        @Volatile
        private var listenerConnected = false
        private val listenerRecovery = NotificationListenerRecovery()

        // A permission grant does not imply a live system binding. In particular,
        // after a process restart there may be no service to receive a disconnect
        // callback, so recovery must also be driven by the foreground service.
        fun ensureListenerConnected(context: Context) {
            val appContext = context.applicationContext
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { ensureListenerConnected(appContext) }
                return
            }
            if (!listenerRecovery.shouldRequestRebind(
                    PermissionUtil.isNotificationListenerEnabled(appContext),
                    listenerConnected,
                    SystemClock.elapsedRealtime()
                )) return
            try {
                Log.i(TAG, "Notification access granted but listener unbound; requesting rebind")
                requestRebind(ComponentName(appContext, MediaNotificationListener::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Notification listener rebind failed", e)
            }
        }

        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.downloads",
            "com.google.android.gms",
            "com.android.vending"
        )

        fun getInstance(): MediaNotificationListener? {
            return serviceInstance
        }

        fun syncActiveNotifications() {
            Handler(Looper.getMainLooper()).post {
                val listener = serviceInstance ?: return@post
                if (!listenerConnected || !WebSocketUtil.isConnected()) return@post
                try {
                    // The Mac may have restarted while this process stayed alive.
                    // Reuse stored IDs so existing Mac cards are updated in place.
                    synchronized(listener.notificationCache) { listener.notificationCache.clear() }
                    val active = listener.activeNotifications.orEmpty()
                    Log.i(TAG, "Resyncing active notification snapshot: ${active.size} entries")
                    active.forEach { listener.processNotificationForSync(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to resync active notifications", e)
                }
            }
        }

    }

    private var serviceJob = kotlinx.coroutines.SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var dataStoreManager: DataStoreManager

    // Cache the most recent notification to suppress duplicate delivery.
    private val notificationCache = LinkedList<Pair<String, String>>()
    private val maxCache = 1

    override fun onCreate() {
        super.onCreate()
        dataStoreManager = DataStoreManager(this)
        serviceInstance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!serviceJob.isActive) {
            serviceJob = kotlinx.coroutines.SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
        }
        serviceInstance = this
        listenerConnected = true
        Log.d(TAG, "Notification listener connected - Ready to sync notifications")
        // Start AirSyncService scanning when listener connects (effectively on boot)
        try {
            if (!AirSyncService.isRunning()) AirSyncService.startScanning(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AirSyncService from listener", e)
        }

        // Re-register notifications that were already in the shade before this
        // process started. Without this, actions and dismissals coming from the
        // client fail with "not found" for every notification that predates the
        // listener connecting. The persisted key->id mapping recovers the exact
        // ID the client already holds even when the notification was updated
        // since (its postTime — embedded in generated IDs — changes on update,
        // while sbn.key stays stable).
        try {
            val currentKeys = mutableSetOf<String>()
            activeNotifications?.forEach { sbn ->
                if (shouldSkipNotification(sbn)) {
                    NotificationDismissalUtil.getIdBySystemKey(sbn.key)?.let {
                        NotificationDismissalUtil.removeFromCaches(it)
                    }
                    return@forEach
                }
                currentKeys.add(sbn.key)
                val title = sbn.notification?.extras?.getString(Notification.EXTRA_TITLE) ?: ""
                val notificationId = NotificationDismissalUtil.getIdBySystemKey(sbn.key)
                    ?: NotificationDismissalUtil.getPersistedIdBySystemKey(sbn.key)
                    ?: NotificationDismissalUtil.generateNotificationId(
                        sbn.packageName,
                        title,
                        sbn.postTime
                    )
                NotificationDismissalUtil.storeNotification(notificationId, sbn)
            }
            NotificationDismissalUtil.prunePersistedMappings(currentKeys)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore active notifications on listener connect", e)
        }

        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")

        serviceJob.cancel()
        ensureListenerConnected(this)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        if (serviceInstance === this) {
            listenerConnected = false
            serviceInstance = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (WebSocketUtil.isConnected()) sbn?.let { processNotificationForSync(it) }
    }


    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (!WebSocketUtil.isConnected()) {
            return
        }
        if (sbn != null) handleNotificationRemoval(sbn)
    }

    private fun handleNotificationRemoval(sbn: StatusBarNotification) {
        Log.d(TAG, "Notification removed: ${sbn.packageName}")

        // Sync dismissal to Mac
        serviceScope.launch {
            try {
                val id = NotificationDismissalUtil.getIdForSbn(sbn) ?: sbn.key

                // If this dismissal was initiated by our own dismiss request, skip echo
                val wasSuppressed = NotificationDismissalUtil.consumeSuppressed(id)
                if (wasSuppressed) {
                    Log.d(TAG, "Dismissal for $id was suppressed (originated from Mac)")
                    NotificationDismissalUtil.removeFromCaches(id)
                    return@launch
                }

                // Build and send update via WebSocket
                val updateJson = JsonUtil.toSingleLine(
                    JsonUtil.createNotificationUpdateJson(
                        id,
                        dismissed = true,
                        action = "dismiss"
                    )
                )
                WebSocketUtil.sendMessage(updateJson)

                Log.d(TAG, "Sent notification removal sync for $id")

                // Remove from caches since it's gone now
                NotificationDismissalUtil.removeFromCaches(id)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing notification removal: ${e.message}")
            }
        }
    }

    private fun processNotificationForSync(sbn: StatusBarNotification) {
        serviceScope.launch {
            try {
                // Check before any preference read or cached action registration.
                if (shouldSkipNotification(sbn)) {
                    NotificationDismissalUtil.getIdBySystemKey(sbn.key)?.let {
                        NotificationDismissalUtil.removeFromCaches(it)
                    }
                    return@launch
                }
                // Skip notifications from AirSync itself to prevent feedback loops
                if (sbn.packageName == packageName) {
                    return@launch
                }

                // Skip Phone app notifications - call handling is done via BroadcastReceiver
                if (sbn.packageName == "com.google.android.dialer") {
                    Log.d(
                        TAG,
                        "Skipping Phone app notification - call handling via BroadcastReceiver"
                    )
                    return@launch
                }

                // Check if notification sync is enabled
                val isSyncEnabled = dataStoreManager.getNotificationSyncEnabled().first()
                if (!isSyncEnabled) {
                    Log.d(TAG, "Notification sync is disabled, skipping notification")
                    return@launch
                }

                // Check if this specific app is enabled for notifications
                val notificationApps = dataStoreManager.getNotificationApps().first()
                val appSettings = notificationApps.find { it.packageName == sbn.packageName }

                // A missing preference is not consent to synchronize an app.
                if (appSettings?.isEnabled != true) {
                    Log.d(TAG, "App ${sbn.packageName} is not enabled for notifications, skipping")
                    return@launch
                }

                val notification = sbn.notification
                val extras = notification.extras

                val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

                // Use big text if available, otherwise use regular text
                val body = bigText?.takeIf { it.isNotEmpty() } ?: text

                // Get app name from package name
                val appName = getAppNameFromPackage(sbn.packageName)

                // Only sync if we have meaningful content
                if (title.isNotEmpty() || body.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "Syncing notification: ${sbn.packageName}"
                    )

                    // Check for duplicate notifications
                    if (isDuplicateNotification(sbn.packageName, body)) {
                        Log.d(TAG, "Duplicate notification detected, skipping sync: ${sbn.packageName}")
                        return@launch
                    }

                    // Retrieve existing notification ID (in-memory, then persisted
                    // from a previous process) or generate a new one
                    val notificationId = NotificationDismissalUtil.getIdBySystemKey(sbn.key)
                        ?: NotificationDismissalUtil.getPersistedIdBySystemKey(sbn.key)
                        ?: NotificationDismissalUtil.generateNotificationId(
                            sbn.packageName,
                            title,
                            sbn.postTime
                        )

                    // Store notification for potential dismissal or actions
                    NotificationDismissalUtil.storeNotification(notificationId, sbn)

                    // Extract actions: button or reply
                    val actions = mutableListOf<Pair<String, String>>()
                    try {
                        val notifActions = notification.actions
                        if (notifActions != null) {
                            for (action in notifActions) {
                                val hasReply =
                                    action.remoteInputs?.any { it.allowFreeFormInput } == true
                                val type = if (hasReply) "reply" else "button"
                                val name = action.title?.toString() ?: "Action"
                                actions.add(name to type)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract actions: ${e.message}")
                    }

                    // Check for progress bar extras
                    var progress: Int? = null
                    var progressMax: Int? = null
                    var progressIndeterminate: Boolean? = null
                    val ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

                    try {
                        val hasProgress = extras.containsKey(Notification.EXTRA_PROGRESS) ||
                                extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
                        if (hasProgress) {
                            val maxVal = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                            val progressVal = extras.getInt(Notification.EXTRA_PROGRESS, 0)
                            val indeterminateVal = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)

                            if (maxVal > 0 || indeterminateVal) {
                                progress = progressVal
                                progressMax = maxVal
                                progressIndeterminate = indeterminateVal
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse notification progress: ${e.message}")
                    }

                    // Get notification priority (alerting vs silent)
                    var priority = getNotificationPriority(sbn)
                    if (progressMax != null || progressIndeterminate == true) {
                        priority = "silent"
                    }

                    // Create notification JSON with actions and progress details
                    val notificationJson = JsonUtil.toSingleLine(
                        JsonUtil.createNotificationJson(
                            id = notificationId,
                            title = title,
                            body = body,
                            app = appName,
                            packageName = sbn.packageName,
                            priority = priority,
                            actions = actions,
                            progress = progress,
                            progressMax = progressMax,
                            progressIndeterminate = progressIndeterminate,
                            ongoing = ongoing
                        )
                    )

                    Log.d(TAG, "Sending notification via WebSocket")
                    val success = WebSocketUtil.sendMessage(notificationJson)
                    if (success) {
                        Log.d(
                            TAG,
                            "Notification sent successfully"
                        )
                    } else {
                        Log.e(TAG, "Failed to send notification")
                    }
                } else {
                    Log.d(TAG, "Skipping empty notification from ${sbn.packageName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing notification: ${e.message}")
            }
        }
    }


    private fun isDuplicateNotification(packageName: String, body: String?): Boolean {
        if (body == null) return false

        synchronized(notificationCache) {
            // Check if the notification is already in the cache
            val isDuplicate = notificationCache.any { it.first == packageName && it.second == body }

            // If not a duplicate, add it to the cache
            if (!isDuplicate) {
                if (notificationCache.size >= maxCache) {
                    notificationCache.removeFirst() // Remove the oldest notification
                }
                notificationCache.add(Pair(packageName, body))
            }

            return isDuplicate
        }
    }

    private fun getNotificationPriority(sbn: StatusBarNotification): String {
        return try {
            val ranking = Ranking()
            if (currentRanking.getRanking(sbn.key, ranking)) {
                val importance = ranking.importance
                if (importance >= NotificationManager.IMPORTANCE_DEFAULT) {
                    "alerting"
                } else {
                    "silent"
                }
            } else {
                "alerting" // Default to alerting if ranking not found
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting notification priority: ${e.message}")
            "alerting"
        }
    }

    private fun shouldSkipNotification(sbn: StatusBarNotification): Boolean {
        return SYSTEM_PACKAGES.contains(sbn.packageName) || NotificationForwardingPolicy.isPlayback(
            sbn.notification.category,
            sbn.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        )
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

}
