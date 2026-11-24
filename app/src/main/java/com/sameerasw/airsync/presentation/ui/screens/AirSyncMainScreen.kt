package com.sameerasw.airsync.presentation.ui.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.sameerasw.airsync.utils.discovery.DiscoverySource
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity
import com.sameerasw.airsync.presentation.ui.components.AirSyncFloatingToolbar
import com.sameerasw.airsync.presentation.ui.components.RoundedCardContainer
import com.sameerasw.airsync.presentation.ui.components.SettingsView
import com.sameerasw.airsync.presentation.ui.components.buttons.ListExpandToggleButton
import com.sameerasw.airsync.presentation.ui.components.cards.ConnectionStatusCard
import com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem
import com.sameerasw.airsync.presentation.ui.components.cards.LastConnectedDeviceCard
import com.sameerasw.airsync.presentation.ui.components.cards.RemoteFunctionsCard
import com.sameerasw.airsync.presentation.ui.components.dialogs.ConnectionDialog
import com.sameerasw.airsync.presentation.ui.components.sheets.HelpSupportBottomSheet
import com.sameerasw.airsync.presentation.ui.models.AirSyncTab
import com.sameerasw.airsync.presentation.ui.modifiers.BlurDirection
import com.sameerasw.airsync.presentation.ui.modifiers.liquidRipple
import com.sameerasw.airsync.presentation.ui.modifiers.progressiveBlur
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.HapticUtil
import com.sameerasw.airsync.utils.JsonUtil
import com.sameerasw.airsync.utils.WebSocketMessageHandler
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLDecoder

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun AirSyncMainScreen(
    modifier: Modifier = Modifier,
    initialIp: String? = null,
    initialPort: String? = null,
    showConnectionDialog: Boolean = false,
    pcName: String? = null,
    isPlus: Boolean = false,
    symmetricKey: String? = null,
    initialPage: Int = 0,
    onNavigateToApps: () -> Unit = {},
    onTitleChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val dataStoreManager = remember { DataStoreManager.getInstance(context) }
    var showConfigureLastDevice by rememberSaveable { mutableStateOf(false) }
    val viewModel: AirSyncViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
        AirSyncViewModel.create(context)
    }
    val uiState by viewModel.uiState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val versionName = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
    } catch (_: Exception) {
        "3.0.0"
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val connectScrollState = rememberScrollState()
    val settingsScrollState = rememberScrollState()
    var hasProcessedQrDialog by remember { mutableStateOf(false) }
    var hasAppliedInitialTab by remember { mutableStateOf(false) }


    var activeSettingsCategory by rememberSaveable { mutableStateOf<String?>(null) }

    fun sendRemoteAction(action: String, value: Any? = null) {
        scope.launch {
            try {
                HapticUtil.performLightTick(haptics)
                val json = JSONObject()
                json.put("type", "remoteControl")
                val data = JSONObject()
                data.put("action", action)
                if (value != null) data.put("value", value)
                json.put("data", data)
                WebSocketUtil.sendMessage(json.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val pagerState =
        rememberPagerState(
            initialPage = initialPage,
            pageCount = { if (uiState.isConnected) { if (uiState.effectiveTransportMode == "relay") 3 else 4 } else 2 })

    LaunchedEffect(pagerState.currentPage) {
        activeSettingsCategory = null
    }

    val navCallbackState = rememberUpdatedState(onNavigateToApps)
    LaunchedEffect(navCallbackState.value) {
    }
    var fabVisible by remember { mutableStateOf(true) }
    var fabExpanded by remember { mutableStateOf(true) }
    var showKeyboard by remember { mutableStateOf(false) } // State for Keyboard Sheet in Remote Tab
    var showHelpSheet by remember { mutableStateOf(false) }
    val onDismissHelp = { showHelpSheet = false }
    var loadingHapticsJob by remember { mutableStateOf<Job?>(null) }

    var rippleTrigger by remember { mutableStateOf(0) }
    var rippleOrigin by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    var wasConnected by remember { mutableStateOf(uiState.isConnected) }
    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected && !wasConnected) {
            val originX = if (rootSize.width > 0) rootSize.width / 2f else 500f
            val originY = if (rootSize.height > 0) rootSize.height * 0.25f else 400f
            rippleOrigin = Offset(originX, originY)
            rippleTrigger++
        }
        wasConnected = uiState.isConnected
    }

    // Initial tab navigation logic
    LaunchedEffect(Unit) {
        if (!hasAppliedInitialTab) {
            if (initialPage != 0) {
                hasAppliedInitialTab = true
                return@LaunchedEffect
            }

            // Wait up to 2 seconds for initial connection (e.g. auto-reconnect on start)
            withTimeoutOrNull(2000) {
                snapshotFlow { uiState.isConnected }.filter { it }.first()
            }

            if (uiState.isConnected) {
                val targetPage = when (uiState.defaultTab) {
                    "connect" -> 0
                    "remote" -> 1
                    "clipboard" -> 2


                    else -> 0
                }
                if (targetPage > 0 && targetPage < (if (uiState.isConnected) 4 else 2)) {
                    pagerState.scrollToPage(targetPage)
                }
            }
            hasAppliedInitialTab = true
        }
    }

    // For export/import flow
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    rememberNavController()

    fun connect(
        deviceId: String? = null,
        ipAddress: String = uiState.ipAddress,
        port: String = uiState.port,
        symmetricKey: String? = uiState.symmetricKey
    ) {
        // Check if critical permissions are missing
        val criticalPermissions =
            com.sameerasw.airsync.utils.PermissionUtil.getCriticalMissingPermissions(context)
        if (criticalPermissions.isNotEmpty()) {
            Toast.makeText(context, context.getString(R.string.ui_missing_permissions), Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.setConnectingDeviceId(deviceId)
        viewModel.setConnectionStatus(isConnected = false, isConnecting = true)
        viewModel.setUserManuallyDisconnected(false)

        scope.launch(Dispatchers.Main) {
            val result = withTimeoutOrNull(20000) {
                var connectionResult: Boolean? = null
                WebSocketUtil.connect(
                    context = context,
                    ipAddress = ipAddress,
                    port = port.toIntOrNull() ?: 6996,
                    symmetricKey = symmetricKey,
                    manualAttempt = true,
                    onHandshakeTimeout = {
                        scope.launch(Dispatchers.Main) {
                            try {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } catch (_: Exception) {
                            }
                            viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                            WebSocketUtil.disconnect(context)
                            viewModel.showAuthFailure(
                                context.getString(R.string.auth_failure_message)
                            )
                        }
                    },
                    onConnectionStatus = { connected ->
                        connectionResult = connected
                    }
                )

                // Wait for the connection result
                while (connectionResult == null) {
                    delay(100)
                }
                connectionResult
            }

            if (result == null) {
                // Timeout occurred
                try {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } catch (_: Exception) {
                }
                viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
                WebSocketUtil.disconnect(context)
                Toast.makeText(context, context.getString(R.string.toast_connection_timed_out), Toast.LENGTH_SHORT).show()
                viewModel.setResponse(context.getString(R.string.toast_connection_timed_out))
            } else {
                val connected = result
                viewModel.setConnectionStatus(isConnected = connected, isConnecting = false)
                if (connected) {
                    viewModel.setResponse(context.getString(R.string.toast_connected_success))
                } else {
                    viewModel.setResponse(context.getString(R.string.connection_failed))
                }
            }
        }
    }

    // CreateDocument launcher for export (MIME application/json)
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.toast_export_cancelled), Toast.LENGTH_SHORT).show()
            viewModel.setLoading(false)
            return@rememberLauncherForActivityResult
        }

        // Write pendingExportJson to uri
        scope.launch(Dispatchers.IO) {
            try {
                val json = pendingExportJson
                if (json == null) {
                    // Nothing to write
                    viewModel.setLoading(false)
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
                    viewModel.setLoading(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.toast_export_failed_detail, e.message ?: ""), Toast.LENGTH_LONG).show()
                    viewModel.setLoading(false)
                }
            }
        }
    }

    // OpenDocument launcher for import (allow picking JSON)
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.toast_import_cancelled), Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        viewModel.setLoading(true)
        scope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)?.bufferedReader()
                    ?.use { it.readText() }
                if (input == null) {
                    scope.launch(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.toast_failed_read_file), Toast.LENGTH_LONG).show()
                        viewModel.setLoading(false)
                    }
                    return@launch
                }

                val success = viewModel.importDataFromJson(context, input)
                scope.launch(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(context, context.getString(R.string.toast_import_success), Toast.LENGTH_SHORT).show()
                        viewModel.initializeState(context)
                    } else {
                        Toast.makeText(context, context.getString(R.string.toast_import_failed), Toast.LENGTH_LONG)
                            .show()
                    }
                    viewModel.setLoading(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.toast_import_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    viewModel.setLoading(false)
                }
            }
        }
    }

    // QR Scanner launcher
    val qrScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val qrCode = result.data?.getStringExtra("QR_CODE")
            if (!qrCode.isNullOrEmpty()) {
                // Parse the QR code (expected format: airsync://ip:port?name=...&plus=...&key=...)
                try {
                    val uri = qrCode.toUri()

                    // Handle potential multiple IPs in host part (e.g., ip1,ip2,ip3)
                    var ip = uri.host ?: ""
                    var port = uri.port.takeIf { it != -1 }?.toString() ?: ""

                    if (ip.isEmpty() || port.isEmpty()) {
                        // Fallback manual parsing if URI host is null due to commas
                        val authority = qrCode.substringAfter("://").substringBefore("?")
                        if (authority.contains(":")) {
                            ip = authority.substringBeforeLast(":")
                            port = authority.substringAfterLast(":")
                        } else {
                            ip = authority
                        }
                    }

                    // Parse query parameters
                    var pcName: String? = null
                    var isPlus = false
                    var symmetricKey: String? = null

                    val queryPart = uri.toString().substringAfter('?', "")
                    if (queryPart.isNotEmpty()) {
                        val params = queryPart.replace('?', '&').split('&')
                        val paramMap = params.associate { param ->
                            val parts = param.split('=', limit = 2)
                            val key = parts.getOrNull(0) ?: ""
                            val value = parts.getOrNull(1) ?: ""
                            key to value
                        }
                        pcName = paramMap["name"]?.let { URLDecoder.decode(it, "UTF-8") }
                        isPlus = paramMap["plus"]?.toBooleanStrictOrNull() ?: false
                        symmetricKey = paramMap["key"]?.let { android.net.Uri.decode(it) }
                    }

                    if (ip.isNotEmpty() && port.isNotEmpty()) {
                        // Update UI state with scanned values
                        viewModel.setTransportMode(context, "local")
                        viewModel.updateIpAddress(ip)
                        viewModel.updatePort(port)
                        viewModel.updateManualPcName(pcName ?: "")
                        if (!symmetricKey.isNullOrEmpty()) {
                            viewModel.updateSymmetricKey(symmetricKey)
                        }

                        // Trigger connection
                        scope.launch {
                            delay(300)  // Brief delay to ensure UI updates
                            connect()
                        }
                    } else {
                        Toast.makeText(context, context.getString(R.string.toast_invalid_qr), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_qr_parse_failed, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }


    fun disconnect() {
        scope.launch {
            WebSocketUtil.disconnect(context, manual = true)
            viewModel.setConnectionStatus(isConnected = false, isConnecting = false)
            viewModel.clearClipboardHistory()
            viewModel.setResponse(context.getString(R.string.disconnected))
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initializeState(
            context,
            initialIp,
            initialPort,
            showConnectionDialog && !hasProcessedQrDialog,
            pcName,
            isPlus,
            symmetricKey
        )

        // Start network monitoring for dynamic Wi-Fi changes
        viewModel.startNetworkMonitoring(context)

        // Refresh permissions on app launch
        viewModel.refreshPermissions(context)
    }

    // Refresh permissions when app resumes from pause
    DisposableEffect(lifecycle) {
        val lifecycleObserver = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions(context)
            }
        }
        lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
        }
    }

    // Mark QR dialog as processed when it's shown or when already connected
    LaunchedEffect(showConnectionDialog, uiState.isConnected) {
        if (showConnectionDialog) {
            if (uiState.isConnected) {
                // If already connected, don't show dialog
                hasProcessedQrDialog = true
                viewModel.setDialogVisible(false)
            } else if (uiState.isDialogVisible) {
                // Dialog is being shown, mark as processed
                hasProcessedQrDialog = true
            }
        }
    }

    // Refresh permissions when returning from settings
    LaunchedEffect(uiState.showPermissionDialog) {
        if (!uiState.showPermissionDialog) {
            viewModel.refreshPermissions(context)
        }
    }

    // Hide FAB on scroll down, show on scroll up for the active tab
    LaunchedEffect(pagerState.currentPage) {
        val state = if (pagerState.currentPage == 0) connectScrollState else settingsScrollState
        val last = state.value
        snapshotFlow { state.value }.collect { value ->
            val delta = value - last
            if (delta > 2) fabVisible = false
            else if (delta < -2) fabVisible = true
        }
    }

    // Expand FAB on first launch and whenever variant changes (connect <-> disconnect), then collapse after 5s
    LaunchedEffect(uiState.isConnected) {
        fabExpanded = true
        // Give users a hint for a short period, then collapse to icon-only
        delay(5000)
        fabExpanded = false
    }

    // The foreground service owns monitoring; the screen only observes history.
    LaunchedEffect(Unit) {
        ClipboardSyncManager.setOnClipboardSentCallback { text ->
            viewModel.addClipboardEntry(text, isFromPc = false)
        }
    }

    LaunchedEffect(Unit) {
        com.sameerasw.airsync.utils.WebSocketMessageHandler.setOnClipboardEntryCallback { text ->
            Log.d(
                "AirSyncMainScreen",
                "Incoming clipboard update via WebSocketMessageHandler"
            )
            viewModel.addClipboardEntry(text, isFromPc = true)
        }
    }

    // Start/stop loading haptics when connecting
    LaunchedEffect(uiState.isConnecting) {
        if (uiState.isConnecting) {
            loadingHapticsJob = HapticUtil.startLoadingHaptics(haptics, lifecycle)
        } else {
            loadingHapticsJob?.cancel()
            loadingHapticsJob = null
        }
    }

    // Auth failure dialog
    if (uiState.showAuthFailureDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissAuthFailure() },
            title = { Text(stringResource(R.string.connection_failed)) },
            text = {
                Text(uiState.authFailureMessage.ifEmpty {
                    stringResource(R.string.auth_failure_message)
                })
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissAuthFailure() }) {
                    Text(stringResource(R.string.ui_ok))
                }
            }
        )
    }


    fun launchScanner(context: Context) {
        // Launch our custom QR Scanner Activity
        val scannerIntent = Intent(context, QRScannerActivity::class.java)
        qrScannerLauncher.launch(scannerIntent)
    }


    fun sendMessage(message: String) {
        scope.launch {
            viewModel.setLoading(true)
            viewModel.setResponse("")

            if (!WebSocketUtil.isConnected()) {
                connect()
                delay(500)
            }

            val success = WebSocketUtil.sendMessage(message)
            if (success) {
                viewModel.setResponse("Message sent: $message")
            } else {
                viewModel.setResponse("Failed to send message")
            }
            viewModel.setLoading(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ClipboardSyncManager.setOnClipboardSentCallback(null)
        }
    }

    // Define tabs
    val tabs = remember(uiState.isConnected, uiState.effectiveTransportMode) {
        if (uiState.isConnected && uiState.effectiveTransportMode == "relay") {
            listOf(
                AirSyncTab(R.string.tab_connect, Icons.Rounded.Phonelink, 0),
                AirSyncTab(R.string.tab_clipboard, Icons.Rounded.ContentPaste, 1),
                AirSyncTab(R.string.tab_settings, Icons.Rounded.Settings, 2)
            )
        } else if (uiState.isConnected) {
            listOf(
                AirSyncTab(R.string.tab_connect, Icons.Rounded.Phonelink, 0),
                AirSyncTab(R.string.tab_remote, Icons.Rounded.Gamepad, 1),
                AirSyncTab(R.string.tab_clipboard, Icons.Rounded.ContentPaste, 2),
                AirSyncTab(R.string.tab_settings, Icons.Rounded.Settings, 3)
            )
        } else {
            listOf(
                AirSyncTab(R.string.tab_connect, Icons.Rounded.Phonelink, 0),
                AirSyncTab(R.string.tab_settings, Icons.Rounded.Settings, 1)
            )
        }
    }

    // Update title based on current tab
    LaunchedEffect(pagerState.currentPage, tabs) {
        val currentTab = tabs.getOrNull(pagerState.currentPage)
        if (currentTab != null) {
            val titleStr = context.getString(currentTab.title)
            val title = if (titleStr == "Connect") "AirSync" else titleStr
            onTitleChange(title)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) { innerPadding ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape =
                configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val statusBarHeightPx = with(density) { statusBarHeight.toPx() }
            val topSpacing = (statusBarHeight - 24.dp).coerceAtLeast(0.dp)

            // Track page changes for haptic feedback on swipe
            LaunchedEffect(pagerState.currentPage) {
                snapshotFlow { pagerState.currentPage }.collect { _ ->
                    HapticUtil.performLightTick(haptics)
                }
            }

            // Blur heights
            val bottomBlurHeightPx = with(density) {
                100.dp.toPx()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .onSizeChanged { rootSize = it }
            ) {
                HorizontalPager(
                    modifier = modifier
                        .fillMaxSize()
                        .liquidRipple(
                            trigger = rippleTrigger,
                            origin = rippleOrigin,
                            enabled = uiState.isRippleSettingEnabled
                        )
                        .progressiveBlur(
                            blurRadius = if (uiState.isBlurEnabled) 40f else 0f,
                            height = statusBarHeightPx * 1.15f,
                            direction = BlurDirection.TOP
                        )
                        .progressiveBlur(
                            blurRadius = if (uiState.isBlurEnabled) 40f else 0f,
                            height = bottomBlurHeightPx,
                            direction = BlurDirection.BOTTOM
                        ),
                    state = pagerState
                ) { page ->
                    val contentPage = if (uiState.isConnected && uiState.effectiveTransportMode == "relay" && page > 0) page + 1 else page
                    when (contentPage) {
                        0 -> {
                            // Connect tab content
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 0.dp)
                                    .verticalScroll(connectScrollState)
                                    .padding(horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {

                                Spacer(
                                    modifier = Modifier
                                        .height(topSpacing)
                                        .fillMaxWidth()
                                )

                                RoundedCardContainer {

                                    // Connection Status Card
                                    ConnectionStatusCard(
                                        isConnected = uiState.isConnected,
                                        isConnecting = uiState.isConnecting,
                                        onDisconnect = { disconnect() },
                                        connectedDevice = uiState.lastConnectedDevice,
                                        lastConnected = uiState.lastConnectedDevice != null,
                                        uiState = uiState,
                                        isPaused = uiState.isAppPaused,
                                        onTogglePause = {
                                            viewModel.setAppPaused(context, !uiState.isAppPaused)
                                        }
                                    )

                                    if (uiState.isConnected) {
                                        com.sameerasw.airsync.presentation.ui.components.SystemVolumeControls()
                                    }

                                    // Remote Functions Card (Lock Screen, etc.)
                                    AnimatedVisibility(
                                        visible = uiState.isConnected && uiState.effectiveTransportMode != "relay",
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        RemoteFunctionsCard(
                                            onRemoteAction = { sendRemoteAction(it) }
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = !uiState.isConnected && !uiState.isAppPaused && uiState.effectiveTransportMode == "local",
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    RoundedCardContainer {
                                    // Nearby Devices (UDP Discovery)
                                    val discoveredDevices by viewModel.discoveredDevices.collectAsState()

                                    // Last Connected Device Section
                                    AnimatedVisibility(
                                        visible = !uiState.isConnected && uiState.lastConnectedDevice != null,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        uiState.lastConnectedDevice?.let { device ->
                                            LastConnectedDeviceCard(
                                                device = device,
                                                onQuickConnect = {
                                                    // Check if we can use network-aware connection first
                                                    val networkAwareDevice =
                                                        viewModel.getNetworkAwareLastConnectedDevice()
                                                    if (networkAwareDevice != null) {
                                                        // Use network-aware device IP for current network
                                                        viewModel.updateIpAddress(networkAwareDevice.ipAddress)
                                                        viewModel.updatePort(networkAwareDevice.port)
                                                        connect(
                                                            ipAddress = networkAwareDevice.ipAddress,
                                                            port = networkAwareDevice.port,
                                                            symmetricKey = networkAwareDevice.symmetricKey
                                                        )
                                                    } else {
                                                        // Fallback to legacy stored device
                                                        viewModel.updateIpAddress(device.ipAddress)
                                                        viewModel.updatePort(device.port)
                                                        viewModel.updateSymmetricKey(device.symmetricKey)
                                                        connect(
                                                            ipAddress = device.ipAddress,
                                                            port = device.port,
                                                            symmetricKey = device.symmetricKey
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = !uiState.isConnected,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut()
                                    ) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.extraSmall,
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceBright
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(bottom = 12.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.label_device_discovery),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )

                                                    Switch(
                                                        checked = uiState.isDeviceDiscoveryEnabled,
                                                        onCheckedChange = { enabled ->
                                                            HapticUtil.performClick(haptics)
                                                            viewModel.setDeviceDiscoveryEnabled(
                                                                context,
                                                                enabled
                                                            )
                                                        },
                                                        thumbContent = if (uiState.isDeviceDiscoveryEnabled) {
                                                            {
                                                                Icon(
                                                                    painter = painterResource(R.drawable.rounded_android_wifi_3_bar_24),
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(
                                                                        SwitchDefaults.IconSize
                                                                    ),
                                                                )
                                                            }
                                                        } else null
                                                    )
                                                }

                                                AnimatedVisibility(
                                                    visible = uiState.isDeviceDiscoveryEnabled,
                                                    enter = expandVertically() + fadeIn(),
                                                    exit = shrinkVertically() + fadeOut()
                                                ) {
                                                    Column {
                                                        if (discoveredDevices.isEmpty()) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(
                                                                    4.dp
                                                                )
                                                            ) {
                                                                LoadingIndicator()

                                                                Text(
                                                                    text = stringResource(R.string.ui_scanning),
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.padding(
                                                                        vertical = 8.dp
                                                                    )
                                                                )
                                                            }
                                                        }

                                                        discoveredDevices.forEachIndexed { index, device ->
                                                            if (index > 0) {
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                            }

                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .clip(MaterialTheme.shapes.medium)
                                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                                    .clickable {
                                                                        HapticUtil.performClick(
                                                                            haptics
                                                                        )
                                                                        val bestIp = device.getBestIp()
                                                                        val devicePort = device.port.toString()
                                                                        val deviceName = device.name

                                                                        viewModel.updateIpAddress(bestIp)
                                                                        viewModel.updatePort(devicePort)
                                                                        viewModel.updateManualPcName(deviceName)

                                                                        val savedKey = viewModel.getSymmetricKeyForDevice(deviceName)
                                                                        if (savedKey != null) {
                                                                            viewModel.updateSymmetricKey(savedKey)
                                                                        }

                                                                        connect(
                                                                            deviceId = device.id,
                                                                            ipAddress = bestIp,
                                                                            port = devicePort,
                                                                            symmetricKey = savedKey ?: uiState.symmetricKey
                                                                        )
                                                                    }
                                                                    .padding(
                                                                        horizontal = 16.dp,
                                                                        vertical = 12.dp
                                                                    ),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(
                                                                    painter = painterResource(R.drawable.apple),
                                                                    contentDescription = null,
                                                                    tint = MaterialTheme.colorScheme.primary
                                                                )
                                                                Spacer(modifier = Modifier.width(12.dp))
                                                                Column {
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Text(
                                                                            text = device.name,
                                                                            style = MaterialTheme.typography.bodyLarge
                                                                        )
                                                                        Spacer(
                                                                            modifier = Modifier.width(
                                                                                8.dp
                                                                            )
                                                                        )
                                                                        if (device.hasLocalIp()) {
                                                                            Icon(
                                                                                painter = painterResource(
                                                                                    R.drawable.rounded_android_wifi_3_bar_24
                                                                                ),
                                                                                contentDescription = "Wi-Fi",
                                                                                modifier = Modifier.size(
                                                                                    14.dp
                                                                                ),
                                                                                tint = MaterialTheme.colorScheme.primary
                                                                            )
                                                                        }
                                                                        if (device.hasTailscaleIp()) {
                                                                            if (device.hasLocalIp()) Spacer(
                                                                                modifier = Modifier.width(
                                                                                    4.dp
                                                                                )
                                                                            )
                                                                            Icon(
                                                                                painter = painterResource(
                                                                                    R.drawable.rounded_network_node_24
                                                                                ),
                                                                                contentDescription = "Tailscale",
                                                                                modifier = Modifier.size(
                                                                                    14.dp
                                                                                ),
                                                                                tint = MaterialTheme.colorScheme.secondary
                                                                            )
                                                                        }
                                                                    }
                                                                    Row(
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = "${device.getBestIp()}:${device.port}",
                                                                            style = MaterialTheme.typography.bodySmall,
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                        )
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .clip(RoundedCornerShape(4.dp))
                                                                                .background(
                                                                                    if (device.discoverySource == DiscoverySource.MDNS)
                                                                                        MaterialTheme.colorScheme.primaryContainer
                                                                                    else
                                                                                        MaterialTheme.colorScheme.secondaryContainer
                                                                                )
                                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                                        ) {
                                                                            Text(
                                                                                text = if (device.discoverySource == DiscoverySource.MDNS) "mDNS" else "UDP",
                                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                                                color = if (device.discoverySource == DiscoverySource.MDNS)
                                                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                                                else
                                                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                                Spacer(modifier = Modifier.weight(1f))
                                                                if (uiState.isConnecting && uiState.connectingDeviceId == device.id) {
                                                                    CircularWavyProgressIndicator(
                                                                        modifier = Modifier.size(20.dp)
                                                                    )
                                                                } else {
                                                                    Icon(
                                                                        Icons.AutoMirrored.Filled.ArrowForward,
                                                                        contentDescription = stringResource(R.string.ui_connect),
                                                                        modifier = Modifier.size(20.dp),
                                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                                AnimatedVisibility(
                                    visible = !uiState.isAppPaused,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ListExpandToggleButton(
                                            isExpanded = showConfigureLastDevice,
                                            onToggle = { showConfigureLastDevice = !showConfigureLastDevice },
                                            title = R.string.action_configure,
                                            iconRes = R.drawable.rounded_settings_24
                                        )

                                        AnimatedVisibility(
                                            visible = showConfigureLastDevice,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            RoundedCardContainer {
                                                IconToggleItem(
                                                    iconRes = R.drawable.rounded_sync_desktop_24,
                                                    title = stringResource(R.string.setting_auto_reconnect_title),
                                                    description = stringResource(R.string.setting_auto_reconnect_desc),
                                                    isChecked = uiState.isAutoReconnectEnabled,
                                                    onCheckedChange = { enabled ->
                                                        viewModel.setAutoReconnectEnabled(enabled)
                                                    }
                                                )


                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(100.dp))
                            }
                        }

                        1 -> {
                            if (uiState.isConnected) {
                                // When connected: page 1 = Remote
                                RemoteControlScreen(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            top = statusBarHeight,
                                            bottom = 100.dp
                                        ),
                                    showKeyboard = showKeyboard,
                                    onDismissKeyboard = { showKeyboard = false }
                                )
                            } else {
                                // When disconnected: page 1 = Settings
                                SettingsNavHost(
                                    context = context,
                                    uiState = uiState,
                                    deviceInfo = deviceInfo,
                                    versionName = versionName,
                                    viewModel = viewModel,
                                    scope = scope,
                                    activeCategory = activeSettingsCategory,
                                    onCategoryChange = { activeSettingsCategory = it },
                                    settingsScrollState = settingsScrollState,
                                    onSendMessage = { message -> sendMessage(message) },
                                    pendingExportJson = { json ->
                                        pendingExportJson = json
                                        createDocLauncher.launch("airsync_settings_${System.currentTimeMillis()}.json")
                                    },
                                    onImport = { openDocLauncher.launch(arrayOf("application/json")) },
                                    onShowHelp = { showHelpSheet = true },
                                    onAvatarLongClickWithPosition = { pos ->
                                        rippleOrigin = pos
                                        rippleTrigger++
                                    },
                                    onRippleToggleEnabledWithPosition = { pos ->
                                        rippleOrigin = pos
                                        rippleTrigger++
                                    }
                                )
                            }
                        }

                        2 -> {
                            if (uiState.isConnected) {
                                // When connected: page 2 = Clipboard
                                ClipboardScreen(
                                    clipboardHistory = uiState.clipboardHistory,
                                    isConnected = true,
                                    onSendText = { text ->
                                        viewModel.addClipboardEntry(text, isFromPc = false)
                                        val clipboardJson = JsonUtil.createClipboardUpdateJson(text)
                                        WebSocketUtil.sendMessage(clipboardJson)
                                    },
                                    onClearHistory = { viewModel.clearClipboardHistory() },
                                    isHistoryEnabled = uiState.isClipboardHistoryEnabled,
                                    onHistoryToggle = { viewModel.setClipboardHistoryEnabled(it) },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            top = topSpacing,
                                            bottom = 100.dp
                                        ),
                                )
                            } else {
                                Box(Modifier.fillMaxSize())
                            }
                        }

                        3 -> {
                            // Page 3 only exists when connected = Settings tab
                            SettingsNavHost(
                                context = context,
                                uiState = uiState,
                                deviceInfo = deviceInfo,
                                versionName = versionName,
                                viewModel = viewModel,
                                scope = scope,
                                activeCategory = activeSettingsCategory,
                                onCategoryChange = { activeSettingsCategory = it },
                                settingsScrollState = settingsScrollState,
                                onSendMessage = { message -> sendMessage(message) },
                                pendingExportJson = { json ->
                                    pendingExportJson = json
                                    createDocLauncher.launch("airsync_settings_${System.currentTimeMillis()}.json")
                                },
                                onImport = { openDocLauncher.launch(arrayOf("application/json")) },
                                onShowHelp = { showHelpSheet = true },
                                onAvatarLongClickWithPosition = { pos ->
                                    rippleOrigin = pos
                                    rippleTrigger++
                                },
                                onRippleToggleEnabledWithPosition = { pos ->
                                    rippleOrigin = pos
                                    rippleTrigger++
                                }
                            )
                        }
                    }
                }

                // Adaptive Bottom Bars Container
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        )
//                    .padding(bottom = 16.dp)
                        .zIndex(2f)
                ) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(
                                16.dp,
                                Alignment.CenterHorizontally
                            ),
                            verticalAlignment = Alignment.Bottom
                        ) {


                            AirSyncFloatingToolbar(
                                modifier = Modifier.zIndex(1f),
                                currentPage = pagerState.currentPage,
                                tabs = tabs,
                                onTabSelected = { index ->
                                    scope.launch {
                                        val distance =
                                            kotlin.math.abs(index - pagerState.currentPage)
                                        if (distance == 1) {
                                            pagerState.animateScrollToPage(index)
                                        } else {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                                },
                                floatingActionButton = {
                                    MainFAB(
                                        currentTab = tabs.getOrNull(pagerState.currentPage),
                                        isConnected = uiState.isConnected,
                                        isAppPaused = uiState.isAppPaused,
                                        activeSettingsCategory = activeSettingsCategory,
                                        onAction = { action ->
                                            when (action) {
                                                "keyboard" -> showKeyboard = !showKeyboard
                                                "clear_history" -> viewModel.clearClipboardHistory()
                                                "disconnect" -> disconnect()
                                                "scan" -> launchScanner(context)
                                                "back" -> activeSettingsCategory = null
                                                "resume" -> viewModel.setAppPaused(context, false)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    } else {
                        // Portrait: Stacked
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {


                            AirSyncFloatingToolbar(
                                modifier = Modifier.zIndex(1f),
                                currentPage = pagerState.currentPage,
                                tabs = tabs,
                                onTabSelected = { index ->
                                    scope.launch {
                                        val distance =
                                            kotlin.math.abs(index - pagerState.currentPage)
                                        if (distance == 1) {
                                            pagerState.animateScrollToPage(index)
                                        } else {
                                            pagerState.scrollToPage(index)
                                        }
                                    }
                                },
                                floatingActionButton = {
                                    MainFAB(
                                        currentTab = tabs.getOrNull(pagerState.currentPage),
                                        isConnected = uiState.isConnected,
                                        isAppPaused = uiState.isAppPaused,
                                        activeSettingsCategory = activeSettingsCategory,
                                        onAction = { action ->
                                            when (action) {
                                                "keyboard" -> showKeyboard = !showKeyboard
                                                "clear_history" -> viewModel.clearClipboardHistory()
                                                "disconnect" -> disconnect()
                                                "scan" -> launchScanner(context)
                                                "back" -> activeSettingsCategory = null
                                                "resume" -> viewModel.setAppPaused(context, false)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Dialogs
        if (uiState.isDialogVisible) {
            ConnectionDialog(
                deviceName = deviceInfo.name,
                localIp = deviceInfo.localIp,
                desktopIp = uiState.ipAddress,
                port = uiState.port,
                pcName = pcName ?: uiState.lastConnectedDevice?.name,
                isPlus = uiState.lastConnectedDevice?.isPlus ?: isPlus,
                onDismiss = { viewModel.setDialogVisible(false) },
                onConnect = {
                    viewModel.setDialogVisible(false)
                    connect()
                }
            )
        }


        // Help & Support Bottom Sheet
        if (showHelpSheet) {
            HelpSupportBottomSheet(
                onDismissRequest = onDismissHelp
            )
        }

    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainFAB(
    currentTab: AirSyncTab?,
    isConnected: Boolean,
    isAppPaused: Boolean,
    activeSettingsCategory: String?,
    onAction: (String) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    FloatingToolbarDefaults.StandardFloatingActionButton(
        onClick = {
            HapticUtil.performClick(haptics)
            if (currentTab?.title == R.string.tab_settings && activeSettingsCategory != null) {
                onAction("back")
            } else {
                when (currentTab?.title) {
                    R.string.tab_remote -> onAction("keyboard")
                    R.string.tab_clipboard -> onAction("clear_history")
                    else -> {
                        if (isConnected) {
                            onAction("disconnect")
                        } else if (isAppPaused) {
                            onAction("resume")
                        } else {
                            onAction("scan")
                        }
                    }
                }
            }
        }
    ) {
        if (currentTab?.title == R.string.tab_settings && activeSettingsCategory != null) {
            Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ui_back))
        } else {
            when (currentTab?.title) {
                R.string.tab_remote -> {
                    Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard")
                }

                R.string.tab_clipboard -> {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.ui_clear_history))
                }

                else -> {
                    if (isConnected) {
                        Icon(imageVector = Icons.Filled.LinkOff, contentDescription = stringResource(R.string.ui_disconnect))
                    } else if (isAppPaused) {
                        Icon(painter = painterResource(R.drawable.rounded_play_arrow_24), contentDescription = stringResource(R.string.resume))
                    } else {
                        Icon(imageVector = Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.ui_scan_qr))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavHost(
    context: Context,
    uiState: com.sameerasw.airsync.domain.model.UiState,
    deviceInfo: com.sameerasw.airsync.domain.model.DeviceInfo,
    versionName: String?,
    viewModel: AirSyncViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    activeCategory: String?,
    onCategoryChange: (String?) -> Unit,
    settingsScrollState: androidx.compose.foundation.ScrollState,
    onSendMessage: (String) -> Unit,
    pendingExportJson: (String) -> Unit,
    onImport: () -> Unit,
    onShowHelp: () -> Unit,
    onAvatarLongClickWithPosition: ((Offset) -> Unit)? = null,
    onRippleToggleEnabledWithPosition: ((Offset) -> Unit)? = null
) {
    var predictiveBackScale by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var predictiveBackOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    LaunchedEffect(activeCategory) {
        if (activeCategory != null) {
            predictiveBackScale = 1f
            predictiveBackOffset = 0f
        }
    }

    val canGoBack = activeCategory != null

    androidx.activity.compose.PredictiveBackHandler(enabled = canGoBack) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                predictiveBackScale = 1f - (backEvent.progress * 0.08f)
                predictiveBackOffset = backEvent.progress * 120f
            }
            onCategoryChange(null)
        } catch (e: java.util.concurrent.CancellationException) {
            predictiveBackScale = 1f
            predictiveBackOffset = 0f
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main settings list
        SettingsView(
            modifier = Modifier.fillMaxSize(),
            context = context,
            innerPaddingBottom = 0.dp,
            uiState = uiState,
            deviceInfo = deviceInfo,
            versionName = versionName,
            viewModel = viewModel,
            activeCategory = null,
            onCategoryChange = onCategoryChange,
            scrollState = settingsScrollState,
            scope = scope,
            onSendMessage = onSendMessage,
            onExport = pendingExportJson,
            onImport = onImport,
            onShowHelp = onShowHelp,
            onToggleDeveloperMode = { viewModel.toggleDeveloperModeVisibility() },
            onAvatarLongClickWithPosition = onAvatarLongClickWithPosition,
            onRippleToggleEnabledWithPosition = onRippleToggleEnabledWithPosition
        )

        // Detail sub-page
        var lastNonNullCategory by remember { mutableStateOf("") }
        if (activeCategory != null) {
            lastNonNullCategory = activeCategory
        }
        val displayedCategory = activeCategory ?: lastNonNullCategory

        AnimatedVisibility(
            visible = activeCategory != null,
            enter = androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(220)
            ) + fadeIn(animationSpec = tween(220)),
            exit = androidx.compose.animation.slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(220)
            ) + fadeOut(animationSpec = tween(220))
        ) {
            if (displayedCategory.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .graphicsLayer {
                            scaleX = predictiveBackScale
                            scaleY = predictiveBackScale
                            translationX = predictiveBackOffset
                        }
                ) {
                    SettingsView(
                        modifier = Modifier.fillMaxSize(),
                        context = context,
                        innerPaddingBottom = 0.dp,
                        uiState = uiState,
                        deviceInfo = deviceInfo,
                        versionName = versionName,
                        viewModel = viewModel,
                        activeCategory = displayedCategory,
                        scrollState = rememberScrollState(),
                        scope = scope,
                        onSendMessage = onSendMessage,
                        onExport = pendingExportJson,
                        onImport = onImport,
                        onShowHelp = onShowHelp,
                        onToggleDeveloperMode = { viewModel.toggleDeveloperModeVisibility() },
                        onAvatarLongClickWithPosition = onAvatarLongClickWithPosition,
                        onRippleToggleEnabledWithPosition = onRippleToggleEnabledWithPosition
                    )
                }
            }
        }
    }
}
