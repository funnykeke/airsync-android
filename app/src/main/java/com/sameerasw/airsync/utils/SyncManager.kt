package com.sameerasw.airsync.utils

import com.sameerasw.airsync.R
import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.domain.model.SystemVolume
import com.sameerasw.airsync.domain.model.BatteryInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object SyncManager {
    private const val TAG = "SyncManager"
    // 自用版本：从 10 秒放宽到 30 秒，减少唤醒次数
    private const val BATTERY_SYNC_INTERVAL = 30_000L // 30 seconds
    private const val MAX_DAILY_ICON_SYNCS = 3

    private var syncJob: Job? = null
    private var lastSystemVolume: SystemVolume? = null
    private var lastBatteryInfo: BatteryInfo? = null
    private val isSyncing = AtomicBoolean(false)
    private val iconSyncInProgress = AtomicBoolean(false)

    fun startPeriodicSync(context: Context) {
        if (isSyncing.get()) {
            Log.d(TAG, "Sync already running")
            return
        }

        isSyncing.set(true)
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Starting periodic sync")

            while (isActive && isSyncing.get()) {
                try {

                    if (WebSocketUtil.isConnected()) {
                        checkAndSyncDeviceStatus(context)
                    }

                    delay(BATTERY_SYNC_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic sync: ${e.message}")
                    delay(BATTERY_SYNC_INTERVAL)
                }
            }
        }
    }

    fun stopPeriodicSync() {
        Log.d(TAG, "Stopping periodic sync")
        isSyncing.set(false)
        syncJob?.cancel()
        syncJob = null
        lastSystemVolume = null
        lastBatteryInfo = null
    }

    fun checkAndSyncDeviceStatus(context: Context, forceSync: Boolean = false) {
        if (!WebSocketUtil.isConnected()) {
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVolume = DeviceInfoUtil.getSystemVolume(context)
                val currentBattery = DeviceInfoUtil.getBatteryInfo(context)
                var shouldSync = forceSync || lastSystemVolume != currentVolume

                // Check if battery info changed significantly
                lastBatteryInfo?.let { last ->
                    if (last.isCharging != currentBattery.isCharging ||
                        kotlin.math.abs(last.level - currentBattery.level) >= 1
                    ) {
                        shouldSync = true
                        Log.d(TAG, "Battery info changed significantly, syncing device status")
                    }
                } ?: run {
                    shouldSync = true // First time
                }

                if (shouldSync) {
                    val statusJson = JsonUtil.createDeviceStatusJson(currentBattery, currentVolume)
                    val success = WebSocketUtil.sendMessage(statusJson)

                    if (success) {
                        lastSystemVolume = currentVolume
                        lastBatteryInfo = currentBattery
                    } else {
                        Log.w(TAG, "Failed to sync device status (WebSocket)")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking device status: ${e.message}")
            }
        }
    }

    fun performInitialSync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Performing initial sync sequence")

            try {
                val dataStoreManager = DataStoreManager.getInstance(context)
                val isRelay = WebSocketUtil.isRelayMode()

                // 1. Ensure ADB discovery is running only in local mode
                if (!isRelay) {
                    try {
                        val activity = context as? com.sameerasw.airsync.MainActivity
                        activity?.initializeAdbDiscovery()
                        Log.d(TAG, "ADB discovery is running")
                        delay(1000)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not access ADB discovery: ${e.message}")
                    }
                }

                // 2. Send lightweight device info immediately (no wallpaper) to trigger macInfo

                // Prefer the persisted device name set in the app. Fall back to system device name only if empty.
                val persistedName = dataStoreManager.getDeviceName().first().ifBlank { null }
                val deviceName =
                    persistedName ?: DeviceInfoUtil.getDeviceName(context).also { sysName ->
                        // Only save system name if no persisted name exists
                        try {
                            dataStoreManager.saveDeviceName(sysName)
                        } catch (_: Exception) {
                        }
                    }
                Log.d(TAG, "Using device name for sync: $deviceName")

                val localIp = if (isRelay) "" else (DeviceInfoUtil.getWifiIpAddress(context) ?: "Unknown")
                val port = dataStoreManager.getPort().first().toIntOrNull() ?: 6996
                val version = context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName ?: "4.3.0"

                // Get discovered ADB ports only in local mode
                val adbPorts = if (isRelay) emptyList() else try {
                    val discoveredServices =
                        com.sameerasw.airsync.AdbDiscoveryHolder.getDiscoveredServices()
                    discoveredServices.map { it.port.toString() }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get ADB ports: ${e.message}")
                    emptyList()
                }
                Log.d(TAG, "Discovered ADB ports: $adbPorts")

                val deviceId = DeviceInfoUtil.getDeviceId(context)
                val liteDeviceInfoJson = JsonUtil.createDeviceInfoJson(
                    id = deviceId,
                    name = deviceName,
                    ipAddress = localIp,
                    port = port,
                    version = version,
                    wallpaperBase64 = null,
                    adbPorts = adbPorts,
                    targetIpAddress = WebSocketUtil.currentIpAddress
                )
                if (WebSocketUtil.sendMessage(liteDeviceInfoJson)) {
                    Log.d(TAG, "Lite device info sent to trigger macInfo")
                } else {
                    Log.e(TAG, "Failed to send lite device info")
                }

                // In local mode, send full device info with wallpaper a bit later without blocking handshake
                if (!isRelay) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            delay(1500)
                            val wallpaperBase64 = try {
                                WallpaperUtil.getWallpaperAsBase64(context)
                            } catch (_: Exception) {
                                null
                            }
                            val discoveredServices = try {
                                com.sameerasw.airsync.AdbDiscoveryHolder.getDiscoveredServices()
                            } catch (e: Exception) {
                                emptyList()
                            }
                            val currentAdbPorts = discoveredServices.map { it.port.toString() }
                            val fullDeviceInfoJson = JsonUtil.createDeviceInfoJson(
                                id = deviceId,
                                name = deviceName,
                                ipAddress = localIp,
                                port = port,
                                version = version,
                                wallpaperBase64 = wallpaperBase64,
                                adbPorts = currentAdbPorts,
                                targetIpAddress = WebSocketUtil.currentIpAddress
                            )
                            if (WebSocketUtil.sendMessage(fullDeviceInfoJson)) {
                                Log.d(
                                    TAG,
                                    "Full device info sent with wallpaper: ${if (wallpaperBase64 != null) "included" else "not available"}"
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send full device info later: ${e.message}")
                        }
                    }
                }

                delay(150)

                // 2. Send battery and system volume, independently of player state.
                val volume = DeviceInfoUtil.getSystemVolume(context)
                val battery = DeviceInfoUtil.getBatteryInfo(context)
                val statusJson = JsonUtil.createDeviceStatusJson(battery, volume)
                if (WebSocketUtil.sendMessage(statusJson)) {
                    Log.d(TAG, "Device status sent")
                    // Update cache
                    lastSystemVolume = volume
                    lastBatteryInfo = battery
                } else {
                    Log.e(TAG, "Failed to send device status")
                }

                // 3. Defer icon sync to macInfo handler to avoid unnecessary extraction
                Log.d(
                    TAG,
                    "Deferring app icon sync until macInfo arrives (to compare package lists first)"
                )

                Log.d(TAG, "Initial sync sequence completed (handshake depends on macInfo)")

            } catch (e: Exception) {
                Log.e(TAG, "Error in initial sync: ${e.message}")
            }
        }
    }

    /**
     * Send updated device info immediately (used when user changes device name in the app).
     */
    fun sendDeviceInfoNow(context: Context, name: String? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ds = DataStoreManager(context)
                val deviceName = name ?: ds.getDeviceName().first()
                    .ifBlank { DeviceInfoUtil.getDeviceName(context) }
                val localIp = DeviceInfoUtil.getWifiIpAddress(context) ?: "Unknown"
                val port = ds.getPort().first().toIntOrNull() ?: 6996
                val version = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }

                // Get discovered ADB ports from the running mDNS discovery
                val adbPorts = try {
                    val discoveredServices =
                        com.sameerasw.airsync.AdbDiscoveryHolder.getDiscoveredServices()
                    discoveredServices.map { it.port.toString() }
                } catch (e: Exception) {
                    emptyList()
                }

                val wallpaperBase64 = if (WebSocketUtil.isRelayMode()) null else try {
                    WallpaperUtil.getWallpaperAsBase64(context)
                } catch (_: Exception) {
                    null
                }
                val deviceId = DeviceInfoUtil.getDeviceId(context)
                val deviceInfoJson = JsonUtil.createDeviceInfoJson(
                    deviceId,
                    deviceName,
                    localIp,
                    port,
                    version,
                    wallpaperBase64,
                    adbPorts,
                    WebSocketUtil.currentIpAddress
                )

                if (WebSocketUtil.sendMessage(deviceInfoJson)) {
                    Log.d(TAG, "Sent updated device info: $deviceName")
                } else {
                    Log.w(TAG, "Failed to send updated device info for $deviceName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending updated device info: ${e.message}")
            }
        }
    }

    /**
     * Check if automatic icon sync is allowed based on daily limit
     */
    private suspend fun shouldSyncIconsAutomatically(context: Context): Boolean {
        try {
            val dataStoreManager = DataStoreManager.getInstance(context)
            val lastDevice = dataStoreManager.getLastConnectedDevice().first()

            if (lastDevice == null) return true // First connection always syncs

            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date())

            // If it's a new day, reset the counter
            if (lastDevice.lastIconSyncDate != today) {
                Log.d(TAG, "New day detected, resetting icon sync counter")
                return true
            }

            // Check if under daily limit
            val isUnderLimit = lastDevice.iconSyncCount < MAX_DAILY_ICON_SYNCS
            Log.d(
                TAG,
                "Icon sync count for today: ${lastDevice.iconSyncCount}/$MAX_DAILY_ICON_SYNCS"
            )

            return isUnderLimit
        } catch (e: Exception) {
            Log.e(TAG, "Error checking icon sync limit: ${e.message}")
            return false
        }
    }

    /**
     * Update icon sync counter after successful sync
     */
    private suspend fun updateIconSyncCounter(context: Context) {
        try {
            val dataStoreManager = DataStoreManager.getInstance(context)
            val lastDevice = dataStoreManager.getLastConnectedDevice().first()

            if (lastDevice != null) {
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())

                val newCount = if (lastDevice.lastIconSyncDate == today) {
                    lastDevice.iconSyncCount + 1
                } else {
                    1 // New day, reset to 1
                }

                val updatedDevice = lastDevice.copy(
                    iconSyncCount = newCount,
                    lastIconSyncDate = today
                )

                dataStoreManager.saveLastConnectedDevice(updatedDevice)
                Log.d(TAG, "Updated icon sync counter: $newCount/$MAX_DAILY_ICON_SYNCS for $today")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating icon sync counter: ${e.message}")
        }
    }

    /**
     * Manually sync app icons (ignores daily limit)
     */
    fun manualSyncAppIcons(context: Context, onResult: (Boolean, String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!WebSocketUtil.isConnected()) {
                    onResult(false, "Not connected to desktop")
                    return@launch
                }

                Log.d(TAG, "Starting manual app icons sync")
                sendAppIcons(context, isManualSync = true, onResult = onResult)
            } catch (e: Exception) {
                Log.e(TAG, "Error in manual icon sync: ${e.message}")
                onResult(false, "Error: ${e.message}")
            }
        }
    }

    private suspend fun sendAppIcons(
        context: Context,
        isManualSync: Boolean = false,
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        try {
            Log.d(TAG, "Starting app icons sync (manual: $isManualSync)")

            val dataStoreManager = DataStoreManager.getInstance(context)

            // Get ALL installed apps first
            val installedApps = AppUtil.getInstalledApps(context)
            Log.d(TAG, "Found ${installedApps.size} installed apps")

            if (installedApps.isEmpty()) {
                val message = context.getString(R.string.ui_no_installed_apps_found)
                Log.w(TAG, message)
                onResult?.invoke(false, message)
                return
            }

            // Get saved notification app preferences
            val savedNotificationApps = dataStoreManager.getNotificationApps().first()

            // Merge installed apps with saved preferences
            val allApps = AppUtil.mergeWithSavedApps(installedApps, savedNotificationApps)

            // Save the merged list back to DataStore
            dataStoreManager.saveNotificationApps(allApps)

            Log.d(
                TAG,
                "Collecting icons for ${allApps.size} apps (${allApps.count { it.isEnabled }} enabled)"
            )

            // Get package names for icon collection
            val allPackages = allApps.map { it.packageName }

            // Get app icons as base64
            val iconMap = AppIconUtil.getAppIconsAsBase64(context, allPackages)

            if (iconMap.isNotEmpty()) {
                // Create and send app icons JSON
                val batches = allApps.chunked(8)

                if (batches.all { batch -> WebSocketUtil.sendMessage(JsonUtil.createAppIconsJson(batch, iconMap)) }) {
                    Log.d(
                        TAG,
                        "✅ App icons sent successfully (${iconMap.size} icons with full app details)"
                    )

                    // Update counter only for automatic syncs
                    if (!isManualSync) {
                        updateIconSyncCounter(context)
                    }

                    val message = context.getString(R.string.sync_icons_success, iconMap.size)
                    onResult?.invoke(true, message)
                } else {
                    Log.e(TAG, "Failed to send app icons")
                    onResult?.invoke(false, context.getString(R.string.sync_icons_failed))
                }
            } else {
                val message = context.getString(R.string.ui_no_app_icons_could_be_collected)
                Log.w(TAG, message)
                onResult?.invoke(false, message)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending app icons: ${e.message}")
            onResult?.invoke(false, "Error: ${e.message}")
        }
    }

    /**
     * Send optimized app icons for a subset of packages (triggered by macInfo)
     */
    fun sendOptimizedAppIcons(
        context: Context,
        packageNames: List<String>,
        fetchIcons: Boolean = true
    ) {
        if (!iconSyncInProgress.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!WebSocketUtil.isConnected()) {
                    Log.w(TAG, "Not connected; cannot send optimized app icons")
                    return@launch
                }

                if (packageNames.isEmpty()) {
                    Log.d(TAG, "No packages to sync icons for")
                    return@launch
                }

                val pm = context.packageManager
                val apps = mutableListOf<com.sameerasw.airsync.domain.model.NotificationApp>()

                val ds = DataStoreManager(context)
                val savedApps = ds.getNotificationApps().first()
                val savedAppsMap = savedApps.associateBy { it.packageName }

                packageNames.forEach { pkg ->
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        val appName = pm.getApplicationLabel(ai).toString()
                        val isSystem =
                            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                    (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                        // App metadata must reflect the same explicit selection as notification delivery.
                        val isEnabled = savedAppsMap[pkg]?.isEnabled ?: false

                        apps.add(
                            com.sameerasw.airsync.domain.model.NotificationApp(
                                packageName = pkg,
                                appName = appName,
                                isEnabled = isEnabled,
                                isSystemApp = isSystem,
                                lastUpdated = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Skipping package $pkg (not found or inaccessible): ${e.message}"
                        )
                    }
                }

                if (apps.isEmpty()) {
                    Log.w(TAG, "No valid apps resolved for optimized icon sync")
                    return@launch
                }

                // A complete package snapshot precedes the icon batches. Each icon frame
                // contains only eight apps, so it cannot be used to remove stale entries.
                val allPackages = org.json.JSONArray()
                apps.forEach { allPackages.put(it.packageName) }
                val snapshot = org.json.JSONObject().put("type", "appList")
                    .put("data", org.json.JSONObject().put("packages", allPackages))
                if (!WebSocketUtil.sendMessage(snapshot.toString())) {
                    Log.e(TAG, "Failed to send app list snapshot")
                    return@launch
                }

                // Extract icons only if requested
                val iconMap = if (fetchIcons) {
                    AppIconUtil.getAppIconsAsBase64(context, apps.map { it.packageName })
                } else {
                    Log.d(TAG, "Skipping icon extraction (metadata sync only)")
                    emptyMap()
                }

                // If verifying icons, we need them. But for metadata sync, empty map is fine.
                if (fetchIcons && iconMap.isEmpty()) Log.w(TAG, "Icon extraction returned empty; sending app names without icons")

                val batches = apps.chunked(8)
                if (batches.all { batch -> WebSocketUtil.sendMessage(JsonUtil.createAppIconsJson(batch, iconMap)) }) {
                    Log.d(
                        TAG,
                        "✅ Optimized app icons sent: ${apps.size} apps (icons included: $fetchIcons)"
                    )
                } else {
                    Log.e(TAG, "Failed to send optimized app icons")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendOptimizedAppIcons: ${e.message}")
            } finally {
                iconSyncInProgress.set(false)
            }
        }
    }

    fun onVolumeChanged(context: Context) {
        Log.d(TAG, "Volume change detected, checking sync")
        checkAndSyncDeviceStatus(context)
    }


    fun reset() {
        lastSystemVolume = null
        lastBatteryInfo = null
    }
}
