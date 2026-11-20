package com.sameerasw.airsync

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.animation.AnticipateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import com.sameerasw.airsync.presentation.ui.screens.AirSyncMainScreen
import com.sameerasw.airsync.ui.theme.AirSyncTheme
import com.sameerasw.airsync.utils.AdbMdnsDiscovery
import com.sameerasw.airsync.utils.DevicePreviewResolver
import com.sameerasw.airsync.utils.PermissionUtil
import com.sameerasw.airsync.utils.ShortcutUtil
import com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator
import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.crash.CrashHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.URLDecoder

object AdbDiscoveryHolder {
    private var discovery: AdbMdnsDiscovery? = null

    fun initialize(context: android.content.Context) {
        if (discovery == null) {
            Log.d("AdbDiscoveryHolder", "Initializing ADB discovery")
            discovery = AdbMdnsDiscovery(context.applicationContext)
            discovery?.startDiscovery()
        }
    }

    fun stop() {
        if (discovery != null) {
            Log.d("AdbDiscoveryHolder", "Stopping ADB discovery")
            discovery?.stopDiscovery()
            discovery = null
        }
    }

    fun restartDiscovery(context: android.content.Context) {
        Log.d("AdbDiscoveryHolder", "Restarting ADB discovery")
        discovery?.stopDiscovery()
        discovery = null
        initialize(context)
    }

    fun isDiscoveryActive(): Boolean {
        return discovery != null
    }

    fun getDiscoveredServices(): List<AdbMdnsDiscovery.AdbServiceInfo> {
        return discovery?.getDiscoveredServices() ?: emptyList()
    }
}

class MainActivity : ComponentActivity() {
    // Flag to keep splash screen visible during app initialization
    private var isAppReady = false

    // Permission launcher for Android 13+ notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrCode = result.data?.getStringExtra("QR_CODE")
            if (qrCode != null) {
                // Parse and connect with the QR code data
                handleQRCodeResult(qrCode)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.sameerasw.airsync.service.MediaNotificationListener.ensureListenerConnected(this)
        com.sameerasw.airsync.service.AirSyncService.notifyAppForeground(this)
    }

    override fun onStop() {
        super.onStop()
        AdbDiscoveryHolder.stop()
        com.sameerasw.airsync.service.AirSyncService.notifyAppBackground(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install and configure the splash screen before any UI rendering
        val splashScreen = installSplashScreen()

        // Enable edge-to-edge for Android 15+ and backward compatibility
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)

        CrashHandler.install(applicationContext)

        // Dynamically set the splash screen icon based on last connected device
        splashScreen.setOnExitAnimationListener { splashScreenViewProvider ->
            try {
                val splashScreenView = splashScreenViewProvider.view
                val splashIcon = try {
                    splashScreenViewProvider.iconView
                } catch (e: Exception) {
                    null
                }

                // Retrieve last connected device in background while showing splash
                var deviceIconRes: Int? = null
                try {
                    val dataStoreManager = DataStoreManager(this@MainActivity)
                    val lastDevice = runBlocking {
                        dataStoreManager.getLastConnectedDevice().first()
                    }

                    // If a last connected device exists, get its preview icon
                    if (lastDevice != null) {
                        deviceIconRes = DevicePreviewResolver.getPreviewRes(lastDevice)
                        Log.d(
                            "MainActivity",
                            "Found last connected device, will crossfade to icon: $deviceIconRes"
                        )
                    } else {
                        Log.d("MainActivity", "No last connected device found")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error retrieving last connected device: ${e.message}", e)
                }

                // Perform crossfade animation if device icon was found
                if (splashIcon is ImageView && deviceIconRes != null) {
                    // Fade out the original app icon
                    val fadeOutIcon = ObjectAnimator.ofFloat(splashIcon, "alpha", 1f, 0f).apply {
                        duration = 150 // 0.15 seconds
                    }

                    fadeOutIcon.doOnEnd {
                        // Switch to device icon - with null check for OEM device compatibility
                        try {
                            splashIcon.setImageResource(deviceIconRes)
                            // Apply Material You primary color tint
                            val colorPrimary = androidx.core.content.ContextCompat.getColor(
                                this@MainActivity,
                                R.color.material_primary
                            )
                            splashIcon.imageTintList =
                                android.content.res.ColorStateList.valueOf(colorPrimary)
                            Log.d("MainActivity", "Switched to device icon with primary tint")

                            // Fade in the new device icon
                            val fadeInIcon =
                                ObjectAnimator.ofFloat(splashIcon, "alpha", 0f, 1f).apply {
                                    duration = 350 // 0.35 seconds
                                }

                            fadeInIcon.doOnEnd {
                                // Hold on device icon for 0.5s, then start outro animation
                                try {
                                    splashScreenView.postDelayed({
                                        startOutroAnimation(
                                            splashScreenView,
                                            splashIcon,
                                            splashScreenViewProvider
                                        )
                                    }, 250) // 0.25 second hold
                                } catch (e: Exception) {
                                    Log.e(
                                        "MainActivity",
                                        "Error scheduling outro animation: ${e.message}",
                                        e
                                    )
                                    // Fallback: start outro immediately
                                    startOutroAnimation(
                                        splashScreenView,
                                        splashIcon,
                                        splashScreenViewProvider
                                    )
                                }
                            }

                            fadeInIcon.start()
                        } catch (e: Exception) {
                            Log.e(
                                "MainActivity",
                                "Error during icon switch/fade in: ${e.message}",
                                e
                            )
                            // Fallback: skip to outro animation if icon switch fails
                            startOutroAnimation(
                                splashScreenView,
                                splashIcon,
                                splashScreenViewProvider
                            )
                        }
                    }

                    fadeOutIcon.start()
                } else {
                    // No device icon found, or splashIcon is null/not ImageView (OEM device compatibility)
                    // Proceed directly to outro after a brief hold
                    try {
                        splashScreenView.postDelayed({
                            startOutroAnimation(
                                splashScreenView,
                                splashIcon,
                                splashScreenViewProvider
                            )
                        }, 500)
                    } catch (e: Exception) {
                        Log.e(
                            "MainActivity",
                            "Error scheduling outro with no icon: ${e.message}",
                            e
                        )
                        // Fallback: start outro immediately
                        startOutroAnimation(
                            splashScreenView,
                            splashIcon,
                            splashScreenViewProvider
                        )
                    }
                }
            } catch (e: Exception) {
                // Fallback for any unexpected exceptions during animation
                Log.e("SplashScreen", "Exception during splash screen animation", e)
                try {
                    splashScreenViewProvider.remove()
                } catch (e2: Exception) {
                    Log.e("SplashScreen", "Exception during splash screen removal", e2)
                }
            }
        }

        // Keep splash screen visible while app is loading
        splashScreen.setKeepOnScreenCondition { !isAppReady }

        // Start ADB discovery only in local mode when permission is granted
        val initialMode = runBlocking {
            DataStoreManager.getInstance(applicationContext).getTransportMode().first()
        }
        if (initialMode == "local" && PermissionUtil.isLocalNetworkPermissionGranted(this)) {
            AdbDiscoveryHolder.initialize(this)
            Log.d("MainActivity", "Started ADB discovery at app startup (local mode)")
        } else {
            AdbDiscoveryHolder.stop()
        }

        // Check if this is a QS tile long-press intent and device is not connected
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            val ds = DataStoreManager.getInstance(applicationContext)
            val isPaused = runBlocking { ds.isAppPaused().first() }
            if (!isPaused && !WebSocketUtil.isConnected()) {
                // Not connected and not paused: pause the app on QS tile long-press
                runBlocking {
                    ds.setAppPaused(true)
                }
                WebSocketUtil.stopAutoReconnect(this)
                WebSocketUtil.disconnect(this, manual = true)
                com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator.stop(this)
                com.sameerasw.airsync.service.AirSyncService.stop(this)
                ShortcutUtil.refreshShortcuts(this, false)
            }
        }

        val data: android.net.Uri? = intent?.data
        val ip = data?.host
        val port = data?.port?.takeIf { it != -1 }?.toString()

        // Parse QR code parameters
        var pcName: String? = null
        var isPlus = false
        var symmetricKey: String? = null

        data?.let { uri ->
            val urlString = uri.toString()
            val queryPart = urlString.substringAfter('?', "")
            if (queryPart.isNotEmpty()) {
                val params = queryPart.replace('?', '&').split('&')
                val paramMap = params.associate {
                    val parts = it.split('=', limit = 2)
                    val key = parts.getOrNull(0) ?: ""
                    val value = parts.getOrNull(1) ?: ""
                    key to value
                }
                pcName = paramMap["name"]?.let { URLDecoder.decode(it, "UTF-8") }
                isPlus = paramMap["plus"]?.toBooleanStrictOrNull() ?: false
                symmetricKey = paramMap["key"]?.let { android.net.Uri.decode(it) }
            }
        }

        val isFromQrScan = data != null

        setContent {
            val viewModel: com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel {
                    com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel.create(this@MainActivity)
                }
            val uiState by viewModel.uiState.collectAsState()

            AirSyncTheme(pitchBlackTheme = uiState.isPitchBlackThemeEnabled) {
                val navController = rememberNavController()

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(
                        0,
                        0,
                        0,
                        0
                    ),
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "main",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("main") {
                            val initialPage =
                                if (intent?.action == ShortcutUtil.DASH_ACTION_REMOTE) 1 else 0
                            AirSyncMainScreen(
                                initialIp = ip,
                                initialPort = port,
                                showConnectionDialog = isFromQrScan,
                                pcName = pcName,
                                isPlus = isPlus,
                                symmetricKey = symmetricKey,
                                initialPage = initialPage
                            )
                        }
                    }
                }

                // Mark app as ready after composing so splash screen can exit
                LaunchedEffect(Unit) {
                    isAppReady = true
                }
            }
        }
    }

    /**
     * Animate the outro of the splash screen: fade out screen and scale down icon
     */
    private fun startOutroAnimation(
        splashScreenView: android.view.View,
        splashIcon: android.view.View?,
        splashScreenViewProvider: androidx.core.splashscreen.SplashScreenViewProvider
    ) {
        try {
            // Animate the splash screen view fade out
            val fadeOut = ObjectAnimator.ofFloat(splashScreenView, "alpha", 1f, 0f).apply {
                interpolator = AnticipateInterpolator()
                duration = 750
            }
            fadeOut.doOnEnd {
                splashScreenViewProvider.remove()
            }

            // Safely animate the icon if it exists (OEM device compatibility)
            try {
                @Suppress("SENSELESS_COMPARISON")
                if (splashIcon != null) {
                    // Scale down animation
                    val scaleX = ObjectAnimator.ofFloat(splashIcon, "scaleX", 1f, 0.2f).apply {
                        interpolator = AnticipateInterpolator()
                        duration = 750
                    }

                    val scaleY = ObjectAnimator.ofFloat(splashIcon, "scaleY", 1f, 0.2f).apply {
                        interpolator = AnticipateInterpolator()
                        duration = 750
                    }

                    scaleX.start()
                    scaleY.start()
                } else {
                    Log.w("SplashScreen", "iconView is null - OEM device detected")
                }
            } catch (e: NullPointerException) {
                Log.w(
                    "SplashScreen",
                    "NullPointerException on iconView animation - likely OEM device",
                    e
                )
            }

            fadeOut.start()
        } catch (e: Exception) {
            Log.e("SplashScreen", "Exception during outro animation", e)
            try {
                splashScreenViewProvider.remove()
            } catch (e2: Exception) {
                Log.e("SplashScreen", "Exception removing splash screen", e2)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionUtil.isPostNotificationPermissionGranted(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }




    private fun handleQRCodeResult(qrCode: String) {
        try {
            val uri = android.net.Uri.parse(qrCode)

            // Validate QR code format: must be airsync scheme with host and port
            if (uri.scheme != "airsync") {
                Log.w("MainActivity", "Invalid QR code scheme: ${uri.scheme}")
                Toast.makeText(
                    this,
                    "Invalid QR code format",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }

            val host = uri.host
            val port = uri.port

            if (host.isNullOrEmpty() || port == -1) {
                Log.w("MainActivity", "Invalid QR code: missing host or port")
                Toast.makeText(
                    this,
                    "Invalid QR code format",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }

            // Rebuild this activity with the new pairing parameters. Starting a
            // singleTop instance and then finishing it closes the pairing screen.
            setIntent(Intent(this, MainActivity::class.java).setData(uri))
            recreate()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling QR code result: ${e.message}", e)
            Toast.makeText(
                this,
                "Invalid QR code format",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent?.data?.scheme == "airsync") {
            recreate()
            return
        }

        // Check if this is a QS tile long-press intent
        if (intent?.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            val ds = DataStoreManager.getInstance(applicationContext)
            val isPaused = runBlocking { ds.isAppPaused().first() }
            // Check if device is disconnected and not paused
            if (!isPaused && !WebSocketUtil.isConnected()) {
                runBlocking {
                    ds.setAppPaused(true)
                }
                WebSocketUtil.stopAutoReconnect(this)
                WebSocketUtil.disconnect(this, manual = true)
                com.sameerasw.airsync.utils.discovery.DiscoveryOrchestrator.stop(this)
                com.sameerasw.airsync.service.AirSyncService.stop(this)
                ShortcutUtil.refreshShortcuts(this, false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ds = DataStoreManager.getInstance(applicationContext)
        val isPaused = runBlocking { ds.isAppPaused().first() }
        if (isPaused) return
        val mode = runBlocking { ds.getTransportMode().first() }
        if (mode != "local") {
            AdbDiscoveryHolder.stop()
            DiscoveryOrchestrator.stop(this)
            return
        }
        if (PermissionUtil.isLocalNetworkPermissionGranted(this)) {
            AdbDiscoveryHolder.initialize(this)
            val isDiscoveryEnabled = runBlocking {
                ds.getDeviceDiscoveryEnabled().first()
            }
            DiscoveryOrchestrator.start(this, isDiscoveryEnabled)
            DiscoveryOrchestrator.burstBroadcast(this)
        }
    }

    /**
     * Ensure ADB discovery is running in local mode.
     */
    fun initializeAdbDiscovery() {
        val mode = runBlocking {
            DataStoreManager.getInstance(applicationContext).getTransportMode().first()
        }
        if (mode == "local" && PermissionUtil.isLocalNetworkPermissionGranted(this)) {
            AdbDiscoveryHolder.initialize(this)
        }
    }

    /**
     * Get discovered ADB services from the running mDNS discovery.
     */
    fun getDiscoveredAdbServices(): List<AdbMdnsDiscovery.AdbServiceInfo> {
        return AdbDiscoveryHolder.getDiscoveredServices()
    }
}
