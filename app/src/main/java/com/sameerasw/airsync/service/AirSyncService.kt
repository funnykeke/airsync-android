package com.sameerasw.airsync.service


import kotlinx.coroutines.withContext
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.MainActivity
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.DiscoveryMode
import com.sameerasw.airsync.utils.MacDeviceStatusManager
import com.sameerasw.airsync.utils.ShortcutUtil
import com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator
import com.sameerasw.airsync.utils.WebDavServer
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.utils.ClipboardSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that maintains the airsync connection and handles discovery.
 *
 * Uses connectedDevice foreground service type as per Google Play Store requirements.
 */
class AirSyncService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var connectedDeviceName: String? = null
    private var isScanning = false

    private var webDavServer: WebDavServer? = null
    private var webDavJob: Job? = null

    // Network state tracking
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val connectionStatusListener: (Boolean) -> Unit = { _ ->
        scope.launch {
            updateNotification()
        }
    }

    private var cachedLastDevice: com.sameerasw.airsync.domain.model.ConnectedDevice? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        Log.d(TAG, "AirSyncService created")
        createNotificationChannel()
        MacDeviceStatusManager.startMonitoring(this)
        registerNetworkCallback()
        WebSocketUtil.registerConnectionStatusListener(connectionStatusListener)

        val dataStoreManager = DataStoreManager.getInstance(applicationContext)
        scope.launch {
            dataStoreManager.getLastConnectedDevice().collect { device ->
                cachedLastDevice = device
                updateNotification()
            }
        }

        scope.launch {
            combine(WebSocketUtil.connectionState, dataStoreManager.getClipboardSyncEnabled(),
                dataStoreManager.getRootClipboardEnabled()) { connected, enabled, root ->
                Pair(connected && enabled, root)
            }.collect { (enabled, root) ->
                ClipboardSyncManager.configure(applicationContext, enabled, root)
            }
        }

        // Monitor connection status, auto-reconnect, and battery status to update notification live
        scope.launch {
            MacDeviceStatusManager.macDeviceStatus.collect {
                updateNotification()
            }
        }

        promoteToForeground()

        scope.launch {
            while (isActive) {
                MediaNotificationListener.ensureListenerConnected(applicationContext)
                delay(30_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AirSyncService started with action: ${intent?.action}")

        val action = intent?.action
        when (action) {
            ACTION_START_SCANNING -> startScanning()
            ACTION_START_SYNC -> {
                connectedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Mac"
                startSync()
                ShortcutUtil.refreshShortcuts(this, true)
            }

            ACTION_STOP_SYNC -> stopSync()
            ACTION_APP_FOREGROUND -> handleAppForeground()
            ACTION_APP_BACKGROUND -> handleAppBackground()
            else -> {
                if (connectedDeviceName != null) {
                    startSync()
                } else {
                    startScanning()
                }
            }
        }

        return START_STICKY
    }

    private fun startScanning() {
        Log.d(TAG, "Starting AirSync scanning mode")
        isScanning = true
        connectedDeviceName = null

        // 中转模式不走局域网扫描，直接发起 relay 连接
        scope.launch {
            val mode = try {
                DataStoreManager.getInstance(applicationContext).getTransportMode().first()
            } catch (e: Exception) {
                "local"
            }
            if (mode == "relay") {
                Log.d(TAG, "Relay mode detected, connecting via relay instead of scanning")
                val ds = DataStoreManager.getInstance(applicationContext)
                withContext(Dispatchers.Main) {
                    WebSocketUtil.connect(
                        context = applicationContext,
                        ipAddress = "",
                        port = 0,
                        symmetricKey = null,
                        manualAttempt = false
                    )
                }
                return@launch
            }
            doLanScanning()
        }
    }

    private suspend fun doLanScanning() {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Starting LAN discovery")
            isScanning = true
            connectedDeviceName = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        scope.launch {
            val dataStoreManager = DataStoreManager.getInstance(applicationContext)
            val isDiscoveryEnabled = dataStoreManager.getDeviceDiscoveryEnabled().first()

            // Default to PASSIVE mode to save battery
            // But do a burst to check for devices immediately
            DiscoveryOrchestrator.start(this@AirSyncService, isDiscoveryEnabled)
            DiscoveryOrchestrator.setDiscoveryMode(this@AirSyncService, DiscoveryMode.PASSIVE)
            DiscoveryOrchestrator.burstBroadcast(this@AirSyncService)

            // Start WakeupService for HTTP wakeups
            WakeupService.startService(this@AirSyncService)

            // Also trigger auto-reconnect logic to check if we already have a candidate
            WebSocketUtil.requestAutoReconnect(this@AirSyncService)
            }   // end scope.launch
        }       // end withContext(Dispatchers.IO)
    }           // end doLanScanning

    private fun startWebDavServer() {
        if (webDavServer == null) {
            webDavServer = WebDavServer(this)
        }
        webDavServer?.start()
    }

    private fun stopWebDavServer() {
        webDavServer?.stop()
        webDavServer = null
    }

    private fun monitorWebDavRequirements() {
        webDavJob?.cancel()
        webDavJob = scope.launch {
            val dataStoreManager = DataStoreManager.getInstance(applicationContext)
            combine(
                dataStoreManager.isFileAccessEnabled(),
                dataStoreManager.getTransportMode()
            ) { isEnabled, mode ->
                isEnabled && mode == "local"
            }.collect { shouldStart ->
                Log.d(TAG, "WebDAV requirement state updated: shouldStart = $shouldStart")
                if (shouldStart) {
                    startWebDavServer()
                } else {
                    stopWebDavServer()
                }
            }
        }
    }

    private fun promoteToForeground() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error promoting to foreground", e)
        }
    }

    private fun handleAppForeground() {
        promoteToForeground()
        MediaNotificationListener.ensureListenerConnected(applicationContext)
        if (isScanning && !WebSocketUtil.isRelayMode()) {
            Log.d(TAG, "App in foreground, switching to ACTIVE discovery")
            DiscoveryOrchestrator.setDiscoveryMode(this, DiscoveryMode.ACTIVE)
        }
    }

    private fun handleAppBackground() {
        promoteToForeground()
        if (isScanning && !WebSocketUtil.isRelayMode()) {
            Log.d(TAG, "App in background, switching to PASSIVE discovery")
            DiscoveryOrchestrator.setDiscoveryMode(this, DiscoveryMode.PASSIVE)
        }
    }

    private fun startSync() {
        if (!isScanning && connectedDeviceName != null) {
            Log.d(TAG, "AirSync foreground service already in sync state, ignoring")
            return
        }
        Log.d(TAG, "Starting AirSync foreground service (connected)")
        isScanning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        scope.launch {
            val dataStoreManager = DataStoreManager.getInstance(applicationContext)
            val mode = dataStoreManager.getTransportMode().first()
            if (mode == "relay") {
                // 中转模式不需要局域网 mDNS/UDP 发现与本地 HTTP 唤醒服务，彻底关闭以省电
                DiscoveryOrchestrator.stop(this@AirSyncService)
                WakeupService.stopService(this@AirSyncService)
                stopWebDavServer()
                return@launch
            }

            val isDiscoveryEnabled = dataStoreManager.getDeviceDiscoveryEnabled().first()

            // Keep discovery manager running for wake-ups in local mode (Passive)
            DiscoveryOrchestrator.start(this@AirSyncService, isDiscoveryEnabled)
            DiscoveryOrchestrator.setDiscoveryMode(this@AirSyncService, DiscoveryMode.PASSIVE)

            WakeupService.startService(this@AirSyncService)
            monitorWebDavRequirements()
        }
    }

    private fun stopSync() {
        Log.d(TAG, "Stopping AirSync foreground service")
        webDavJob?.cancel()
        webDavJob = null
        stopWebDavServer()
        ShortcutUtil.refreshShortcuts(this, false)
        DiscoveryOrchestrator.stop(this)
        WakeupService.stopService(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager =
                getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val builder = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (WebSocketUtil.isRelayMode()) return
                    Log.d(
                        TAG,
                        "Network available, triggering burst broadcast and refreshing socket"
                    )
                    // Refresh UDP socket to bind to new network interface
                    DiscoveryOrchestrator.refreshSocket()
                    // When network becomes available, do a burst to announce ourselves
                    if (isScanning) {
                        DiscoveryOrchestrator.burstBroadcast(applicationContext)
                        WebSocketUtil.requestAutoReconnect(applicationContext)
                    }
                }
            }

            connectivityManager.registerNetworkCallback(builder.build(), networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_status),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.ui_shows_airsync_connection_and_discovery_status)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating foreground notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val disconnectIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent =
            PendingIntent.getBroadcast(this, 1, disconnectIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_laptop_24)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val isConnected = WebSocketUtil.isConnected()
        val isAuto = WebSocketUtil.isAutoReconnecting()
        val isConnecting = WebSocketUtil.isConnecting()

        val lastDevice = cachedLastDevice
        val macStatus = MacDeviceStatusManager.macDeviceStatus.value

        val batteryText = macStatus?.let { status ->
            val level = status.battery.level
            if (level >= 0) {
                val pct = level.coerceIn(0, 100)
                if (status.battery.isCharging) " ($pct% " + getString(R.string.notif_charging) + ")" else " ($pct%)"
            } else ""
        } ?: ""

        if (WebSocketUtil.isRelayMode()) {
            builder.setContentTitle(getString(R.string.notif_relay_title))
            val state = WebSocketUtil.status.value
            builder.setContentText(
                state.error ?: when (state.phase) {
                    com.sameerasw.airsync.utils.ConnectionPhase.CONNECTED ->
                        getString(R.string.connected_to_device, lastDevice?.name ?: "Mac") + batteryText
                    com.sameerasw.airsync.utils.ConnectionPhase.WAITING_FOR_PEER ->
                        getString(R.string.notif_relay_waiting)
                    com.sameerasw.airsync.utils.ConnectionPhase.HANDSHAKING ->
                        getString(R.string.notif_relay_handshaking)
                    com.sameerasw.airsync.utils.ConnectionPhase.RECONNECTING ->
                        getString(R.string.notif_relay_reconnecting)
                    else -> getString(R.string.notif_relay_connecting)
                }
            )
            builder.addAction(
                R.drawable.rounded_link_off_24,
                getString(R.string.disconnect),
                disconnectPendingIntent
            )
        } else if (isConnected && lastDevice != null) {
            val name = lastDevice.name
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText(getString(R.string.connected_to_device, name) + batteryText)
            builder.addAction(
                R.drawable.rounded_link_off_24,
                getString(R.string.disconnect),
                disconnectPendingIntent
            )
        } else if (isAuto) {
            builder.setContentTitle(getString(R.string.notif_reconnecting))
            builder.setContentText(
                getString(if (isConnecting) R.string.notif_trying_connect else R.string.notif_waiting_retry)
            )
        } else if (isConnecting) {
            builder.setContentTitle(getString(R.string.notif_connecting))
            builder.setContentText(getString(R.string.connecting_last_device))
        } else {
            builder.setContentTitle(getString(R.string.app_name))
            builder.setContentText(getString(R.string.no_device_connected))
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "AirSyncService destroyed")
        ClipboardSyncManager.shutdown()
        serviceInstance = null
        WebSocketUtil.unregisterConnectionStatusListener(connectionStatusListener)

        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }

        stopWebDavServer()
        MacDeviceStatusManager.stopMonitoring()
        MacDeviceStatusManager.cleanup(this)
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AirSyncService"
        private const val CHANNEL_ID = "airsync_connection_channel"
        private const val NOTIFICATION_ID = 4001

        const val ACTION_START_SCANNING = "com.sameerasw.airsync.START_SCANNING"
        const val ACTION_START_SYNC = "com.sameerasw.airsync.START_SYNC"
        const val ACTION_STOP_SYNC = "com.sameerasw.airsync.STOP_SYNC"
        const val ACTION_DISCONNECT = "com.sameerasw.airsync.DISCONNECT_FROM_NOTIFICATION"
        const val ACTION_APP_FOREGROUND = "com.sameerasw.airsync.APP_FOREGROUND"
        const val ACTION_APP_BACKGROUND = "com.sameerasw.airsync.APP_BACKGROUND"

        const val EXTRA_DEVICE_NAME = "device_name"

        private var serviceInstance: AirSyncService? = null

        fun isRunning(): Boolean = serviceInstance != null

        fun startScanning(context: Context) {
            runBlocking {
                val dataStoreManager = DataStoreManager.getInstance(context)
                if (dataStoreManager.isAppPaused().first()) {
                    Log.d(TAG, "App is paused, ignoring startScanning")
                    return@runBlocking
                }
                val intent = Intent(context, AirSyncService::class.java).apply {
                    action = ACTION_START_SCANNING
                }
                startAction(context, intent)
            }
        }

        fun start(context: Context, deviceName: String?) {
            runBlocking {
                val dataStoreManager = DataStoreManager.getInstance(context)
                if (dataStoreManager.isAppPaused().first()) {
                    Log.d(TAG, "App is paused, ignoring start")
                    return@runBlocking
                }
                val intent = Intent(context, AirSyncService::class.java).apply {
                    action = ACTION_START_SYNC
                    putExtra(EXTRA_DEVICE_NAME, deviceName)
                }
                startAction(context, intent)
            }
        }

        fun notifyAppForeground(context: Context) {
            if (!isRunning()) return
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_APP_FOREGROUND
            }
            startAction(context, intent)
        }

        fun notifyAppBackground(context: Context) {
            if (!isRunning()) return
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_APP_BACKGROUND
            }
            startAction(context, intent)
        }

        private fun startAction(context: Context, intent: Intent) {
            try {
                if (isRunning()) {
                    context.startService(intent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AirSyncService::class.java).apply {
                action = ACTION_STOP_SYNC
            }
            context.startService(intent)
        }
    }
}
