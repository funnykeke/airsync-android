package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.HapticUtil

@Composable
fun DeveloperModeCard(
    isDeveloperMode: Boolean,
    onToggleDeveloperMode: (Boolean) -> Unit,
    isLoading: Boolean,
    onSendDeviceInfo: () -> Unit,
    onSendNotification: () -> Unit,
    onSendDeviceStatus: () -> Unit,
    onExportData: () -> Unit,
    onImportData: () -> Unit,
    isIconSyncLoading: Boolean,
    iconSyncMessage: String,
    onManualSyncIcons: () -> Unit,
    onClearIconSyncMessage: () -> Unit,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            IconToggleItem(
                iconRes = R.drawable.rounded_troubleshoot_24,
                title = stringResource(R.string.ui_developer_mode),
                isChecked = isDeveloperMode,
                onCheckedChange = onToggleDeveloperMode
            )

            if (isDeveloperMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.dev_section_test_functions),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onSendDeviceInfo()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text(stringResource(R.string.dev_send_device_info))
                    }

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onSendNotification()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text(stringResource(R.string.dev_send_test_notification))
                    }

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onSendDeviceStatus()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Text(stringResource(R.string.dev_send_device_status))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
                                onExportData()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading
                        ) {
                            Text(stringResource(R.string.dev_export_data))
                        }

                        Button(
                            onClick = {
                                HapticUtil.performClick(haptics)
                                onImportData()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isLoading
                        ) {
                            Text(stringResource(R.string.dev_import_data))
                        }
                    }





                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dev_section_icons),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            onManualSyncIcons()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isConnected && !isIconSyncLoading
                    ) {
                        if (isIconSyncLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                if (isIconSyncLoading) R.string.dev_syncing_icons else R.string.dev_sync_app_icons
                            )
                        )
                    }

                    AnimatedVisibility(
                        visible = iconSyncMessage.isNotEmpty(),
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        val isIconSyncSuccess = iconSyncMessage.contains("Successfully") || iconSyncMessage.contains("成功")
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraSmall,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isIconSyncSuccess)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = iconSyncMessage,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isIconSyncSuccess)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(onClick = {
                                    HapticUtil.performClick(haptics)
                                    onClearIconSyncMessage()
                                }) {
                                    Text(stringResource(R.string.action_dismiss), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.dev_section_crash_reporting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = {
                            HapticUtil.performClick(haptics)
                            throw RuntimeException("Test Crash from Developer Options")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.dev_simulate_crash))
                    }
                }
            }
        }
    }
}
