package com.sameerasw.airsync.presentation.ui.components

import com.sameerasw.airsync.utils.WebSocketUtil
import com.sameerasw.airsync.presentation.ui.components.cards.TransportModeCard
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.domain.model.DeviceInfo
import com.sameerasw.airsync.domain.model.UiState
import com.sameerasw.airsync.presentation.ui.components.buttons.ListExpandToggleButton
import com.sameerasw.airsync.presentation.ui.components.cards.ClipboardFeaturesCard
import com.sameerasw.airsync.presentation.ui.components.cards.RootClipboardSettings
import com.sameerasw.airsync.presentation.ui.components.cards.DefaultTabCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeveloperModeCard
import com.sameerasw.airsync.presentation.ui.components.cards.DeviceInfoCard
import com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem
import com.sameerasw.airsync.presentation.ui.components.cards.ManualConnectionCard
import com.sameerasw.airsync.presentation.ui.components.cards.MediaSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.NotificationSyncCard
import com.sameerasw.airsync.presentation.ui.components.cards.PermissionsCard
import com.sameerasw.airsync.presentation.ui.components.cards.QuickSettingsTilesCard
import com.sameerasw.airsync.presentation.ui.components.sheets.AppSelectionSheet
import com.sameerasw.airsync.presentation.viewmodel.AirSyncViewModel
import com.sameerasw.airsync.utils.HapticUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SettingsView component that displays all settings cards in a scrollable column.
 * Can be used both when disconnected and when connected.
 *
 * @param modifier Modifier to apply to the settings view
 * @param context Android context for utilities
 * @param innerPaddingBottom Bottom padding for safe areas
 * @param uiState The current UI state containing all settings data
 * @param deviceInfo Device information data
 * @param versionName Application version name
 * @param viewModel ViewModel for handling state updates
 * @param scrollState Scroll state for the column
 * @param scope Coroutine scope for async operations
 * @param onSendMessage Callback to send messages
 * @param onExport Callback for export action
 * @param onImport Callback for import action
 */
@Composable
fun SettingsView(
    modifier: Modifier = Modifier,
    context: Context,
    innerPaddingBottom: androidx.compose.ui.unit.Dp,
    uiState: UiState,
    deviceInfo: DeviceInfo,
    versionName: String?,
    viewModel: AirSyncViewModel,
    activeCategory: String? = null,
    onCategoryChange: (String?) -> Unit = {},
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    scope: CoroutineScope = androidx.compose.runtime.rememberCoroutineScope(),
    onSendMessage: (String) -> Unit = {},
    onExport: (String) -> Unit = {},
    onImport: () -> Unit = {},
    onShowHelp: () -> Unit = {},
    onToggleDeveloperMode: () -> Unit = {},
    onAvatarLongClickWithPosition: ((Offset) -> Unit)? = null,
    onRippleToggleEnabledWithPosition: ((Offset) -> Unit)? = null
) {
    val haptics = LocalHapticFeedback.current
    var showAppSelectionSheet by remember { mutableStateOf(false) }
    var showMediaAppSelectionSheet by remember { mutableStateOf(false) }
    var showManualConnection by remember { mutableStateOf(false) }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val minHeaderHeight = 200.dp
    val maxHeaderHeight = 500.dp
    var headerHeight by remember { mutableStateOf(minHeaderHeight) }

    LaunchedEffect(activeCategory) {
        headerHeight = minHeaderHeight
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (delta < 0 && headerHeight > minHeaderHeight) {
                    val oldHeight = headerHeight
                    headerHeight = with(density) {
                        (oldHeight.toPx() + delta).toDp()
                    }.coerceAtLeast(minHeaderHeight)
                    val consumed = oldHeight - headerHeight
                    return Offset(0f, with(density) { -consumed.toPx() })
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val delta = available.y
                if (delta > 0) {
                    val oldHeight = headerHeight
                    headerHeight = with(density) {
                        (oldHeight.toPx() + delta).toDp()
                    }.coerceAtMost(maxHeaderHeight)

                    if (headerHeight == maxHeaderHeight && oldHeight < maxHeaderHeight) {
                        HapticUtil.performClick(haptics)
                    }

                    val produced = headerHeight - oldHeight
                    return Offset(0f, with(density) { produced.toPx() })
                }
                return Offset.Zero
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = innerPaddingBottom)
            .nestedScroll(nestedScrollConnection)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val topSpacing = (statusBarHeight - 24.dp).coerceAtLeast(0.dp)

        Spacer(
            modifier = Modifier
                .height(topSpacing)
                .fillMaxWidth()
        )

        if (activeCategory == null) {
            // Top
            RoundedCardContainer {
                PermissionsCard(missingPermissionsCount = uiState.missingPermissions.size)

                // Help and guides card
                com.sameerasw.airsync.presentation.ui.components.cards.IconToggleItem(
                    iconRes = com.sameerasw.airsync.R.drawable.rounded_info_24,
                    title = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.label_help_guides),
                    description = androidx.compose.ui.res.stringResource(com.sameerasw.airsync.R.string.subtitle_help_guides),
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onShowHelp()
                    }
                )

                QuickSettingsTilesCard(
                    isConnectionTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                        context,
                        com.sameerasw.airsync.service.AirSyncTileService::class.java
                    ),
                    isClipboardTileAdded = com.sameerasw.airsync.utils.QuickSettingsUtil.isQSTileAdded(
                        context,
                        com.sameerasw.airsync.service.ClipboardTileService::class.java
                    )
                )
            }

            // Connection Section
            SettingsCategory(title = stringResource(R.string.ui_connection)) {
                DeviceInfoCard(
                    deviceName = uiState.deviceNameInput,
                    localIp = deviceInfo.localIp,
                    onDeviceNameChange = { viewModel.updateDeviceName(it) }
                )

// 自用版本：已用「服务器中转」替代扩展网络
            }

            AnimatedVisibility(
                visible = !uiState.isConnected && !uiState.isAppPaused,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListExpandToggleButton(
                        isExpanded = showManualConnection,
                        onToggle = { showManualConnection = !showManualConnection },
                        title = R.string.action_manual_connection,
                    )

                    AnimatedVisibility(
                        visible = showManualConnection,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        RoundedCardContainer {
                            ManualConnectionCard(
                                isConnected = uiState.isConnected,
                                lastConnected = uiState.lastConnectedDevice != null,
                                uiState = uiState,
                                onIpChange = { viewModel.updateIpAddress(it) },
                                onPortChange = { viewModel.updatePort(it) },
                                onPcNameChange = { viewModel.updateManualPcName(it) },
                                onSymmetricKeyChange = {
                                    viewModel.updateSymmetricKey(it)
                                },
                                onConnect = { viewModel.prepareForManualConnection() }
                            )
                        }
                    }
                }
            }

            // Settings Categories Section
            SettingsCategory(title = stringResource(R.string.ui_settings)) {
                IconToggleItem(
                    title = stringResource(R.string.ui_app),
                    description = stringResource(R.string.ui_tab_defaults_theme_and_more),
                    iconRes = R.drawable.rounded_devices_24,
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onCategoryChange("App")
                    }
                )

                IconToggleItem(
                    title = stringResource(R.string.ui_notifications),
                    description = stringResource(R.string.ui_notifications_and_per_app_settings),
                    iconRes = R.drawable.rounded_notifications_active_24,
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onCategoryChange("Notifications")
                    }
                )

                IconToggleItem(
                    title = stringResource(R.string.ui_clipboard),
                    description = stringResource(R.string.ui_clipboard_and_link_settings),
                    iconRes = R.drawable.ic_clipboard_24,
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onCategoryChange("Clipboard")
                    }
                )

                IconToggleItem(
                    title = stringResource(R.string.ui_media_and_files),
                    description = stringResource(R.string.ui_media_controls_quick_share_and_more),
                    iconRes = R.drawable.rounded_folder_managed_24,
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onCategoryChange("Media and Files")
                    }
                )

                IconToggleItem(
                    title = stringResource(R.string.ui_widget),
                    description = stringResource(R.string.ui_home_screen_widget_customization),
                    iconRes = R.drawable.rounded_web_24,
                    showToggle = false,
                    onClick = {
                        HapticUtil.performClick(haptics)
                        onCategoryChange("Widget")
                    }
                )
            }

            AboutSection(
                onAvatarLongClick = onToggleDeveloperMode,
                onAvatarLongClickWithPosition = onAvatarLongClickWithPosition
            )

            // Advanced / Developer Section
            AnimatedVisibility(
                visible = uiState.isDeveloperModeVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                SettingsCategory(title = stringResource(R.string.ui_advanced)) {
                    DeveloperModeCard(
                        isDeveloperMode = uiState.isDeveloperMode,
                        onToggleDeveloperMode = { viewModel.setDeveloperMode(it) },
                        isLoading = uiState.isLoading,
                        onSendDeviceInfo = {
                            val adbPorts = try {
                                val discoveredServices =
                                    com.sameerasw.airsync.AdbDiscoveryHolder.getDiscoveredServices()
                                discoveredServices.map { it.port.toString() }
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val deviceId =
                                com.sameerasw.airsync.utils.DeviceInfoUtil.getDeviceId(context)
                            val message = com.sameerasw.airsync.utils.JsonUtil.createDeviceInfoJson(
                                deviceId,
                                deviceInfo.name,
                                deviceInfo.localIp,
                                uiState.port.toIntOrNull() ?: 6996,
                                versionName ?: "3.0.0",
                                adbPorts
                            )
                            onSendMessage(message)
                        },
                        onSendNotification = {
                            val testNotification =
                                com.sameerasw.airsync.utils.TestNotificationUtil.generateRandomNotification()

                            // Store ID for mock dismissal support
                            com.sameerasw.airsync.utils.NotificationDismissalUtil.storeTestNotificationId(
                                testNotification.id
                            )

                            val message =
                                com.sameerasw.airsync.utils.JsonUtil.createNotificationJson(
                                    testNotification.id,
                                    testNotification.title,
                                    testNotification.body,
                                    testNotification.appName,
                                    testNotification.packageName,
                                    testNotification.priority,
                                    testNotification.actions
                                )
                            onSendMessage(message)
                        },
                        onSendDeviceStatus = {
                            scope.launch(Dispatchers.IO) {
                                val message = com.sameerasw.airsync.utils.DeviceInfoUtil.generateDeviceStatusJson(context)
                                onSendMessage(message)
                            }
                        },
                        onExportData = {
                            viewModel.setLoading(true)
                            scope.launch(Dispatchers.IO) {
                                val json = viewModel.exportAllDataToJson(context)
                                if (json == null) {
                                    scope.launch(Dispatchers.Main) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.toast_export_failed),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        viewModel.setLoading(false)
                                    }
                                } else {
                                    scope.launch(Dispatchers.Main) {
                                        onExport(json)
                                    }
                                }
                            }
                        },
                        onImportData = {
                            onImport()
                        },
                        isIconSyncLoading = uiState.isIconSyncLoading,
                        iconSyncMessage = uiState.iconSyncMessage,
                        onManualSyncIcons = {
                            viewModel.manualSyncAppIcons(context)
                        },
                        onClearIconSyncMessage = {
                            viewModel.clearIconSyncMessage()
                        },
                        isConnected = uiState.isConnected,

                    )
                }
            }
        } else {
            // Sub-Settings category view
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Category header animation
                Box(
                    modifier = Modifier.fillMaxWidth().height(headerHeight),
                    contentAlignment = Alignment.Center
                ) {
                    when (activeCategory) {
                        "Notifications" -> NotifyAnimation(
                            isPlus = uiState.isConnected && (uiState.lastConnectedDevice?.isPlus == true),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(headerHeight)
                        )
                        "Clipboard" -> ClipAnimation(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(headerHeight)
                        )
                        "Media and Files" -> FileAnimation(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(headerHeight)
                        )
                        else -> AirSyncLoadingAnimation(
                            isPlus = uiState.isConnected && (uiState.lastConnectedDevice?.isPlus == true),
                            modifier = Modifier.size(headerHeight)
                        )
                    }
                }

                val categoryTitle = when (activeCategory) {
                    "App" -> stringResource(R.string.ui_app)
                    "Notifications" -> stringResource(R.string.ui_notifications)
                    "Clipboard" -> stringResource(R.string.ui_clipboard)
                    "Media and Files" -> stringResource(R.string.ui_media_and_files)
                    "Widget" -> stringResource(R.string.ui_widget)
                    else -> activeCategory
                }

                Text(
                    text = categoryTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                RoundedCardContainer {
                    when (activeCategory) {
                        "App" -> {
                            TransportModeCard(
                                transportMode = uiState.transportMode,
                                isConnected = uiState.isConnected,
                                isConnecting = uiState.isConnecting,
                                relayServerUrl = uiState.relayServerUrl,
                                relayRoom = uiState.relayRoom,
                                relaySecret = uiState.relaySecret,
                                relayPinnedCertSha256 = uiState.relayPinnedCertSha256,
                                encryptionKey = uiState.symmetricKey,
                                errorMessage = uiState.relayError,
                                onModeChange = { mode -> viewModel.setTransportMode(context, mode) },
                                onServerUrlChange = { url -> viewModel.setRelayServerUrl(context, url) },
                                onRoomChange = { r -> viewModel.setRelayRoom(context, r) },
                                onSecretChange = { sec -> viewModel.setRelaySecret(context, sec) },
                                onPinnedCertSha256Change = { pin -> viewModel.setRelayPinnedCertSha256(context, pin) },
                                onEncryptionKeyChange = { key -> viewModel.updateSymmetricKey(key) },
                                onSaveAndConnect = { viewModel.saveRelayAndConnect(context) },
                                onDisconnect = { WebSocketUtil.disconnect(context) }
                            )

                            DefaultTabCard(
                                currentDefaultTab = uiState.defaultTab,
                                onDefaultTabChange = { tab -> viewModel.setDefaultTab(tab) }
                            )

                            IconToggleItem(
                                title = stringResource(R.string.label_use_blur),
                                description = when {
                                    com.sameerasw.airsync.utils.DeviceInfoUtil.isBlurProblematicDevice() ->
                                        stringResource(R.string.subtitle_blur_disabled_samsung)

                                    uiState.isPowerSaveMode && uiState.isBlurSettingEnabled ->
                                        stringResource(R.string.subtitle_blur_disabled_power_save)

                                    else -> stringResource(R.string.subtitle_use_blur)
                                },
                                iconRes = R.drawable.rounded_blur_on_24,
                                isChecked = uiState.isBlurSettingEnabled,
                                onCheckedChange = { enabled: Boolean ->
                                    viewModel.setUseBlurEnabled(enabled, context)
                                },
                                enabled = !com.sameerasw.airsync.utils.DeviceInfoUtil.isBlurProblematicDevice()
                            )

                            IconToggleItem(
                                title = stringResource(R.string.label_ripple_animation),
                                iconRes = R.drawable.rounded_blur_linear_24,
                                isChecked = uiState.isRippleSettingEnabled,
                                onCheckedChange = { enabled: Boolean ->
                                    viewModel.setUseRippleEnabled(enabled)
                                },
                                onCheckedChangeWithPosition = { isChecked, pos ->
                                    if (isChecked) {
                                        onRippleToggleEnabledWithPosition?.invoke(pos)
                                    }
                                }
                            )

                            IconToggleItem(
                                title = stringResource(R.string.label_pitch_black_theme),
                                iconRes = R.drawable.rounded_dark_mode_24,
                                isChecked = uiState.isPitchBlackThemeEnabled,
                                onCheckedChange = { enabled: Boolean ->
                                    viewModel.setPitchBlackThemeEnabled(enabled)
                                }
                            )

                            IconToggleItem(
                                title = stringResource(R.string.label_notify_on_crash),
                                description = stringResource(R.string.subtitle_notify_on_crash),
                                iconRes = R.drawable.rounded_bug_report_24,
                                isChecked = uiState.isNotifyOnCrashEnabled,
                                onCheckedChange = { enabled: Boolean ->
                                    viewModel.setNotifyOnCrashEnabled(enabled)
                                }
                            )
                        }
                        "Notifications" -> {
                            NotificationSyncCard(
                                isNotificationEnabled = uiState.isNotificationEnabled,
                                isNotificationSyncEnabled = uiState.isNotificationSyncEnabled,
                                onToggleSync = { enabled ->
                                    viewModel.setNotificationSyncEnabled(enabled)
                                },
                                onGrantPermissions = { viewModel.setPermissionDialogVisible(true) }
                            )

                            if (uiState.isNotificationSyncEnabled && uiState.isNotificationEnabled) {
                                IconToggleItem(
                                    title = stringResource(R.string.action_select_apps),
                                    description = stringResource(R.string.subtitle_to_be_notified),
                                    iconRes = R.drawable.rounded_notification_settings_24,
                                    showToggle = false,
                                    onClick = {
                                        HapticUtil.performClick(haptics)
                                        viewModel.loadNotificationApps(context)
                                        showAppSelectionSheet = true
                                    }
                                )
                            }
                        }
                        "Clipboard" -> {
                            ClipboardFeaturesCard(
                                isClipboardSyncEnabled = uiState.isClipboardSyncEnabled,
                                onToggleClipboardSync = { enabled: Boolean ->
                                    viewModel.setClipboardSyncEnabled(enabled)
                                },
                                isContinueBrowsingEnabled = uiState.isContinueBrowsingEnabled,
                                onToggleContinueBrowsing = { enabled: Boolean ->
                                    viewModel.setContinueBrowsingEnabled(enabled)
                                },
                                isContinueBrowsingToggleEnabled = true,
                                continueBrowsingSubtitle = stringResource(R.string.ui_prompt_to_open_shared_links_in_browser),
                                isKeepPreviousLinkEnabled = uiState.isKeepPreviousLinkEnabled,
                                onToggleKeepPreviousLink = { enabled: Boolean ->
                                    viewModel.setKeepPreviousLinkEnabled(enabled)
                                }
                            )
                            RootClipboardSettings(context, uiState.isClipboardSyncEnabled) {
                                viewModel.refreshPermissions(context)
                            }
                        }
                        "Media and Files" -> {
                            MediaSyncCard(
                                isSendNowPlayingEnabled = uiState.isSendNowPlayingEnabled,
                                onToggleSendNowPlaying = { enabled ->
                                    viewModel.setSendNowPlayingEnabled(enabled)
                                },
                                isMacMediaControlsEnabled = uiState.isMacMediaControlsEnabled,
                                onToggleMacMediaControls = { enabled ->
                                    viewModel.setMacMediaControlsEnabled(enabled)
                                },
                                onOpenExcludedApps = {
                                    HapticUtil.performClick(haptics)
                                    viewModel.loadMediaApps(context)
                                    showMediaAppSelectionSheet = true
                                }
                            )

                            IconToggleItem(
                                title = stringResource(R.string.ui_quick_share),
                                description = stringResource(R.string.ui_allow_receiving_files_from_nearby_devices),
                                iconRes = R.drawable.quick_share,
                                isChecked = uiState.isQuickShareEnabled,
                                onCheckedChange = { enabled: Boolean ->
                                    viewModel.setQuickShareEnabled(context, enabled)
                                }
                            )

                            IconToggleItem(
                                title = stringResource(R.string.label_file_access),
                                description = stringResource(R.string.subtitle_file_access),
                                iconRes = R.drawable.rounded_folder_managed_24,
                                isChecked = uiState.isFileAccessEnabled,
                                onCheckedChange = { enabled: Boolean ->
                                    viewModel.setFileAccessEnabled(context, enabled)
                                }
                            )
                        }
                        "Widget" -> {
                            com.sameerasw.airsync.presentation.ui.components.sliders.ConfigSliderItem(
                                title = stringResource(R.string.ui_widget_transparency),
                                value = uiState.widgetTransparency,
                                onValueChange = { viewModel.setWidgetTransparency(it) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(220.dp))
    }

    if (showAppSelectionSheet) {
        val apps by viewModel.notificationApps.collectAsState()
        AppSelectionSheet(
            onDismissRequest = { showAppSelectionSheet = false },
            apps = apps,
            onAppToggle = { pkg, enabled ->
                viewModel.toggleNotificationApp(context, pkg, enabled)
            },
            onSaveAll = { updatedList ->
                viewModel.saveAllNotificationApps(context, updatedList)
            },
            isLoading = apps.isEmpty()
        )
    }

    if (showMediaAppSelectionSheet) {
        val mediaApps by viewModel.mediaApps.collectAsState()
        AppSelectionSheet(
            onDismissRequest = { showMediaAppSelectionSheet = false },
            apps = mediaApps,
            onAppToggle = { pkg, isExcluded ->
                viewModel.toggleExcludedMediaPackage(pkg, isExcluded)
            },
            onSaveAll = { updatedList ->
                viewModel.saveAllMediaApps(updatedList)
            },
            isLoading = mediaApps.isEmpty()
        )
    }
}

@Composable
fun SettingsCategoryTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

@Composable
fun SettingsCategory(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsCategoryTitle(title)
        RoundedCardContainer {
            content()
        }
    }
}
