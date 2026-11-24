package com.sameerasw.airsync.utils

import com.sameerasw.airsync.R
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.sameerasw.airsync.BuildConfig
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.data.repository.AirSyncRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Central handler for all incoming WebSocket messages from the Mac server.
 * Dispatches messages to specific handler methods based on the 'type' field.
 */
object WebSocketMessageHandler {
    private const val TAG = "WebSocketMessageHandler"


    // Callback for clipboard entry history tracking
    private var onClipboardEntryReceived: ((text: String) -> Unit)? = null

    fun setOnClipboardEntryCallback(callback: ((text: String) -> Unit)?) {
        onClipboardEntryReceived = callback
        Log.d(
            TAG,
            "Clipboard entry callback ${if (callback != null) "registered" else "unregistered"}"
        )
    }

    private val _macSystemVolume = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    val macSystemVolume = _macSystemVolume.asStateFlow()

    private var onModifierStatusReceived: ((JSONObject) -> Unit)? = null

    fun setOnModifierStatusCallback(callback: ((JSONObject) -> Unit)?) {
        onModifierStatusReceived = callback
    }

    /**
     * Handle incoming WebSocket messages from Mac.
     * Parses the JSON payload and routes to the appropriate private handler method.
     *
     * @param context Application context for performing actions.
     * @param message Raw JSON message string.
     */
    internal fun handleIncomingMessage(context: Context, json: String, fileSession: WebSocketUtil.FileSession?) {
        Log.d(TAG, "Received business message")
        try {
            val jsonObject = JSONObject(json)
            val type = jsonObject.optString("type")
            if (!TransportCapabilities.allows(WebSocketUtil.status.value.mode, type)) return
            Log.d(TAG, "Processing message type: $type")
            val data = jsonObject.optJSONObject("data") ?: JSONObject()

            if (type != "ping") {
                Log.d(TAG, "Handling message type: $type")
            }

            when (type) {
                "clipboardUpdate" -> handleClipboardUpdate(context, data)
                "volumeControl" -> handleVolumeControl(context, data)
                "dismissNotification" -> handleNotificationDismissal(data)
                "notificationAction" -> handleNotificationAction(data)
                "disconnectRequest" -> handleDisconnectRequest(context)
                "toggleAppNotif" -> handleToggleAppNotification(context, data)
                "macVolume" -> handleMacVolume(data)
                "modifierStatus" -> handleModifierStatus(data)
                "ping" -> handlePing(context)
                "status" -> handleMacDeviceStatus(context, data)
                "macInfo" -> handleMacInfo(context, data)
                "refreshAdbPorts" -> handleRefreshAdbPorts(context)
                "browseLs" -> fileSession?.let { LocalFileTransfer.browse(data, it) }
                "startQuickShare" -> handleStartQuickShare(context)
                "filePull", "fileTransferInit", "fileChunk", "fileChunkAck", "fileTransferComplete", "fileTransferCancel", "transferVerified" -> fileSession?.let { LocalFileTransfer.handle(context, type, data, it) }
                else -> {
                    Log.w(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message: ${e.message}")
        }
    }

    // MARK: - Clipboard & Control Handlers

    /**
     * Updates the system clipboard with text from the Mac.
     * Uses `ClipboardSyncManager` to avoid feedback loops by tracking origin.
     */
    private fun handleClipboardUpdate(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Clipboard update data is null")
                return
            }

            val text = data.optString("text")
            if (!text.isNullOrEmpty()) {
                Log.d(TAG, "Clipboard update received from desktop")

                // Notify ViewModel/UI to add entry to clipboard history
                onClipboardEntryReceived?.invoke(text)

                // Update system clipboard
                ClipboardSyncManager.handleClipboardUpdate(context, text)
                Log.d(TAG, "Clipboard updated from desktop")
            } else {
                Log.w(TAG, "Clipboard update received but text is empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard update: ${e.message}")
        }
    }

    /**
     * Handles volume control commands (set, increase, decrease, mute).
     * Sends a response back to the Mac indicating success or failure.
     */
    private fun handleVolumeControl(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Volume control data is null")
                sendVolumeControlResponse("setVolume", false, "No data provided")
                return
            }

            when (val action = data.optString("action")) {
                "setVolume" -> {
                    val volume = data.optInt("volume", -1)
                    if (volume in 0..100) {
                        val success = VolumeControlUtil.setVolume(context, volume)
                        sendVolumeControlResponse(
                            action,
                            success,
                            if (success) "Volume set to $volume%" else "Failed to set volume"
                        )

                        // Send updated device status after volume change
                        if (success) {
                            SyncManager.onVolumeChanged(context)
                        }
                    } else {
                        sendVolumeControlResponse(action, false, "Invalid volume value: $volume")
                    }
                }

                "increaseVolume" -> {
                    val increment = data.optInt("increment", 10)
                    val success = VolumeControlUtil.increaseVolume(context, increment)
                    sendVolumeControlResponse(
                        action,
                        success,
                        if (success) "Volume increased by $increment%" else "Failed to increase volume"
                    )

                    if (success) {
                        SyncManager.onVolumeChanged(context)
                    }
                }

                "decreaseVolume" -> {
                    val decrement = data.optInt("decrement", 10)
                    val success = VolumeControlUtil.decreaseVolume(context, decrement)
                    sendVolumeControlResponse(
                        action,
                        success,
                        if (success) "Volume decreased by $decrement%" else "Failed to decrease volume"
                    )

                    if (success) {
                        SyncManager.onVolumeChanged(context)
                    }
                }

                "toggleMute" -> {
                    val success = VolumeControlUtil.toggleMute(context)
                    sendVolumeControlResponse(
                        action,
                        success,
                        if (success) "Mute toggled" else "Failed to toggle mute"
                    )

                    if (success) {
                        SyncManager.onVolumeChanged(context)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown volume control action: $action")
                    sendVolumeControlResponse(action, false, "Unknown action")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling volume control: ${e.message}")
            sendVolumeControlResponse("unknown", false, "Error: ${e.message}")
        }
    }


    /**
     * Attempts to dismiss a notification on the Android device by ID.
     */
    private fun handleNotificationDismissal(data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Notification dismissal data is null")
                sendNotificationDismissalResponse("unknown", false, "No data provided")
                return
            }

            val notificationId = data.optString("id")
            if (notificationId.isEmpty()) {
                sendNotificationDismissalResponse(
                    notificationId,
                    false,
                    "No notification ID provided"
                )
                return
            }

            val success = NotificationDismissalUtil.dismissNotification(notificationId)
            val message =
                if (success) "Notification dismissed" else "Failed to dismiss notification or not found"

            sendNotificationDismissalResponse(notificationId, success, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification dismissal: ${e.message}")
            sendNotificationDismissalResponse("unknown", false, "Error: ${e.message}")
        }
    }

    /**
     * Executes an action on a notification (e.g., Reply, Archive).
     * Supports both action buttons and direct replies.
     */
    private fun handleNotificationAction(data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Notification action data is null")
                sendNotificationActionResponse("unknown", "", false, "No data provided")
                return
            }

            val notificationId = data.optString("id")
            if (notificationId.isEmpty()) {
                sendNotificationActionResponse(
                    notificationId,
                    "",
                    false,
                    "No notification ID provided"
                )
                return
            }

            // We accept either "name" or legacy "action" for action name
            val actionName = data.optString("name", data.optString("action", "")).ifEmpty { "" }
            // Absent "text" must stay null: an empty string would be taken as an
            // inline reply and plain action buttons would never be invoked.
            val replyText = data.optString("text").takeIf { it.isNotEmpty() }

            if (actionName.isEmpty()) {
                sendNotificationActionResponse(
                    notificationId,
                    actionName,
                    false,
                    "No action name provided"
                )
                return
            }

            val success = NotificationDismissalUtil.performNotificationAction(
                notificationId,
                actionName,
                replyText
            )
            val message = if (success) {
                if (!replyText.isNullOrEmpty()) "Reply sent" else "Action invoked"
            } else {
                "Failed to perform action or notification not found"
            }

            sendNotificationActionResponse(notificationId, actionName, success, message)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification action: ${e.message}")
            sendNotificationActionResponse("unknown", "", false, "Error: ${e.message}")
        }
    }

    private fun handlePing(context: Context) {
        try {
            // Reply immediately with lightweight pong message to keep session active
            val pongJson = "{\"type\":\"pong\",\"data\":{}}"
            WebSocketUtil.sendMessage(pongJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ping: ${e.message}")
        }
    }

    private fun handleDisconnectRequest(context: Context) {
        try {
            WebSocketUtil.disconnect(context, manual = true)
            Log.d(TAG, "WebSocket disconnected as per request")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling disconnect request: ${e.message}")
        }
    }

    /**
     * Processes battery and identity updates received from the Mac.
     * Updates local storage and triggers widget refresh if needed.
     */
    private fun handleMacDeviceStatus(context: Context, data: JSONObject?) {
        try {
            if (data == null) {
                Log.e(TAG, "Mac device status data is null")
                return
            }


            // Parse battery information
            val battery = data.optJSONObject("battery")
            val batteryLevel = battery?.optInt("level", 0) ?: 0
            val isCharging = battery?.optBoolean("isCharging", false) ?: false

            MacDeviceStatusManager.updateStatus(
                context = context,
                name = data.optString("name", MacDeviceStatusManager.macDeviceStatus.value?.name ?: "Unknown"),
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                isPaired = data.optBoolean("isPaired", true)
            )

            // Persist a lightweight snapshot for widget consumption and throttle widget refresh
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val ds = DataStoreManager(context)
                    ds.saveMacStatusForWidget(batteryLevel, isCharging)

                    // Throttle widget updates to once per minute to reduce battery usage
                    val lastRefresh = ds.getMacWidgetRefreshedAt().first() ?: 0L
                    val now = System.currentTimeMillis()
                    if (now - lastRefresh >= 30_000L) {
                        com.sameerasw.airsync.widget.AirSyncWidgetProvider.updateAllWidgets(context)
                        ds.setMacWidgetRefreshedAt(now)
                    }
                } catch (_: Exception) {
                }
            }

            Log.d(TAG, "Mac device status updated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling Mac device status: ${e.message}")
        }
    }

    /**
     * Handles the 'macInfo' handshake/update message containing Mac specs and installed apps.
     * Updates device info in the database and synchronizes app icons if needed.
     */
    private fun handleMacInfo(context: Context, data: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (data == null) {
                    Log.e(TAG, "macInfo data is null")
                    return@launch
                }

                val macName = data.optString("name", "")
                val isPlus = true
                val macVersion = data.optString("version", "3.0.0")

                Log.d(
                    TAG,
                    "Processing macInfo - name: '$macName', isPlus: $isPlus, version: '$macVersion'"
                )

                // Version compatibility check
                val minVersion = BuildConfig.MIN_MAC_APP_VERSION
                if (isVersionOutdated(macVersion, minVersion)) {
                    if (com.sameerasw.airsync.AirSyncApp.isAppForeground()) {
                        launch(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.mac_app_outdated, macVersion, minVersion),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                val savedAppPackagesJson = data.optJSONArray("savedAppPackages")
                val savedPackages = mutableSetOf<String>()
                if (savedAppPackagesJson != null) {
                    for (i in 0 until savedAppPackagesJson.length()) {
                        val pkg = savedAppPackagesJson.optString(i)
                        if (!pkg.isNullOrBlank()) savedPackages.add(pkg)
                    }
                }

                // Update last connected device info with Mac name and Plus flag
                try {
                    val ds = DataStoreManager(context)
val last = ds.getLastConnectedDevice().first() ?: com.sameerasw.airsync.domain.model.ConnectedDevice(
    name = macName.ifBlank { "Mac" }, ipAddress = WebSocketUtil.currentIpAddress ?: "",
    port = "6996", lastConnected = System.currentTimeMillis(), isPlus = true,
    symmetricKey = if (WebSocketUtil.isRelayMode()) ds.getRelaySymmetricKey().first() else null
)

                    if (last != null) {
                        // Extract model and device type from macInfo
                        val model = data.optString("model", "").ifBlank { null }
                        val deviceType = when {
                            data.has("type") -> data.optString("type", "").ifBlank { null }
                            data.has("deviceType") -> data.optString("deviceType", "")
                                .ifBlank { null }

                            else -> null
                        }

                        Log.d(
                            TAG,
                            "Updating device: name='${if (macName.isNotBlank()) macName else last.name}', isPlus=$isPlus, model='$model', type='$deviceType'"
                        )

                        ds.saveLastConnectedDevice(
                            last.copy(
                                name = if (macName.isNotBlank()) macName else last.name,
                                isPlus = isPlus,
                                lastConnected = System.currentTimeMillis(),
                                model = model,
                                deviceType = deviceType
                            )
                        )

                        Log.d(TAG, "Device info updated successfully in storage")

                        // Also update the network-aware device storage if possible
                        try {
                            val ourIp = DeviceInfoUtil.getWifiIpAddress(context) ?: ""
                            val clientIp = last.ipAddress
                            val port = last.port
                            val symmetricKey = last.symmetricKey

                            if (!WebSocketUtil.isRelayMode() && !WebSocketUtil.isUsbMode() && clientIp.isNotBlank() && ourIp.isNotBlank()) {
                                ds.saveNetworkDeviceConnection(
                                    deviceName = if (macName.isNotBlank()) macName else last.name,
                                    ourIp = ourIp,
                                    clientIp = clientIp,
                                    port = port,
                                    isPlus = isPlus,
                                    symmetricKey = symmetricKey,
                                    model = model,
                                    deviceType = deviceType
                                )
                                Log.d(TAG, "Network device info also updated successfully")
                            }
                        } catch (e: Exception) {
                            Log.w(
                                TAG,
                                "Unable to update network device info from macInfo: ${e.message}"
                            )
                        }

                        // Force update the last connected timestamp for network device as well
                        try {
                            if (macName.isNotBlank()) {
                                ds.updateNetworkDeviceLastConnected(
                                    macName,
                                    System.currentTimeMillis()
                                )
                                Log.d(TAG, "Network device timestamp updated")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Unable to update network device timestamp: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to update connected device info from macInfo: ${e.message}")
                }

                // Build Android launcher package list (lightweight)
                val androidPackages = try {
                    AppUtil.getLauncherPackageNames(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get launcher package names: ${e.message}")
                    emptyList()
                }

                // Decide how to sync icons based on differences between Android and Mac package lists
                val androidSet = androidPackages.toSet()
                val savedSet = savedPackages.toSet()

                if (savedSet.isEmpty()) {
                    // Mac has none; send full current Android list
                    Log.d(
                        TAG,
                        "macInfo: Mac has no saved packages; syncing full list of ${androidPackages.size} apps"
                    )
                    SyncManager.sendOptimizedAppIcons(context, androidPackages)
                    return@launch
                }

                val newOnAndroid = androidSet - savedSet // apps present on Android but not on Mac
                val missingOnAndroid =
                    savedSet - androidSet // apps present on Mac but uninstalled on Android

                if (newOnAndroid.isNotEmpty() || missingOnAndroid.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "macInfo: App list changed (new=${newOnAndroid.size}, missing=${missingOnAndroid.size}); syncing full list of ${androidPackages.size} apps"
                    )
                    // Send the full current Android list so desktop can add new and remove missing
                    SyncManager.sendOptimizedAppIcons(context, androidPackages, fetchIcons = true)
                } else {
                    Log.d(
                        TAG,
                        "macInfo: No app list changes; skipping icon extraction but syncing metadata"
                    )
                    // Sync metadata (enabled/disabled states) without re-sending heavy icon data
                    SyncManager.sendOptimizedAppIcons(context, androidPackages, fetchIcons = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling macInfo: ${e.message}")
            }
        }
    }


    private fun sendVolumeControlResponse(action: String, success: Boolean, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val response = JsonUtil.createVolumeControlResponse(action, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }


    private fun sendNotificationDismissalResponse(id: String, success: Boolean, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val response = JsonUtil.createNotificationDismissalResponse(id, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }

    private fun sendNotificationActionResponse(
        id: String,
        actionName: String,
        success: Boolean,
        message: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val response =
                JsonUtil.createNotificationActionResponse(id, actionName, success, message)
            WebSocketUtil.sendMessage(response)
        }
    }

    private fun handleToggleAppNotification(context: Context, data: JSONObject?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (data == null) {
                    Log.e(TAG, "Toggle app notification data is null")
                    return@launch
                }

                val packageName = data.optString("package")
                val stateString = data.optString("state")

                if (packageName.isEmpty()) {
                    Log.e(TAG, "Package name is empty in toggle app notification")
                    return@launch
                }

                val newState = stateString.toBoolean()

                Log.d(TAG, "Toggling notification for package: $packageName to state: $newState")

                // Get the repository
                val dataStoreManager = DataStoreManager(context)
                val repository = AirSyncRepositoryImpl(dataStoreManager)

                // Get current apps
                val currentApps = repository.getNotificationApps().first().toMutableList()

                // Find and update the app
                val appIndex = currentApps.indexOfFirst { it.packageName == packageName }

                if (appIndex != -1) {
                    // Update existing app
                    val updatedApp = currentApps[appIndex].copy(isEnabled = newState)
                    currentApps[appIndex] = updatedApp

                    // Save updated apps
                    repository.saveNotificationApps(currentApps)

                    Log.d(
                        TAG,
                        "Successfully updated notification state for $packageName to $newState"
                    )

                    // Send confirmation response back
                    val responseMessage = JsonUtil.createToggleAppNotificationResponse(
                        packageName = packageName,
                        success = true,
                        newState = newState,
                        message = context.getString(R.string.ui_app_notification_state_updated_successfully)
                    )
                    WebSocketUtil.sendMessage(responseMessage)
                } else {
                    Log.w(TAG, "App with package name $packageName not found in notification apps")

                    // Send error response
                    val responseMessage = JsonUtil.createToggleAppNotificationResponse(
                        packageName = packageName,
                        success = false,
                        newState = newState,
                        message = context.getString(R.string.ui_app_not_found)
                    )
                    WebSocketUtil.sendMessage(responseMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling toggle app notification: ${e.message}")
                val packageName = data?.optString("package") ?: ""
                val newState = data?.optString("state")?.toBoolean() ?: false
                val responseMessage = JsonUtil.createToggleAppNotificationResponse(
                    packageName = packageName,
                    success = false,
                    newState = newState,
                    message = "Error: ${e.message}"
                )
                WebSocketUtil.sendMessage(responseMessage)
            }
        }
    }


    private fun handleMacVolume(data: JSONObject?) {
        try {
            if (data == null) return
            val volume = data.optInt("volume", -1)
            if (volume >= 0) {
                Log.d(TAG, "Received Mac volume update: $volume")
                _macSystemVolume.value = volume.coerceIn(0, 100)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling macVolume: ${e.message}")
        }
    }

    private fun handleModifierStatus(data: JSONObject?) {
        try {
            if (data == null) return
            Log.d(TAG, "Received modifier status update: $data")
            onModifierStatusReceived?.invoke(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling modifierStatus: ${e.message}")
        }
    }

    private fun handleRefreshAdbPorts(context: Context) {
        Log.d(TAG, "Request to refresh ADB ports received. Restarting discovery...")
        com.sameerasw.airsync.AdbDiscoveryHolder.restartDiscovery(context)

        CoroutineScope(Dispatchers.IO).launch {
            delay(2500)
            Log.d(TAG, "Sending refreshed device info with ADB ports after delay")
            SyncManager.sendDeviceInfoNow(context)
        }
    }

    private fun isVersionOutdated(current: String, min: String): Boolean {
        return try {
            val currentParts = current.split(".").map { it.toInt() }
            val minParts = min.split(".").map { it.toInt() }

            val maxLen = maxOf(currentParts.size, minParts.size)
            for (i in 0 until maxLen) {
                val currentPart = if (i < currentParts.size) currentParts[i] else 0
                val minPart = if (i < minParts.size) minParts[i] else 0

                if (currentPart < minPart) return true
                if (currentPart > minPart) return false
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun handleStartQuickShare(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = DataStoreManager.getInstance(context)
                val enabled = ds.isQuickShareEnabled().first()
                if (!enabled) {
                    return@launch
                }

                Log.d(TAG, "Triggering Quick Share receiving mode via WebSocket")
                val intent = Intent(
                    context,
                    com.sameerasw.airsync.quickshare.QuickShareService::class.java
                ).apply {
                    action =
                        com.sameerasw.airsync.quickshare.QuickShareService.ACTION_START_DISCOVERY
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Quick Share service: ${e.message}")
            }
        }
    }
}
