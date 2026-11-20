package com.sameerasw.airsync.presentation.ui.components.dialogs

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sameerasw.airsync.R

enum class PermissionType {
    NOTIFICATION_ACCESS,
    POST_NOTIFICATIONS,
    BACKGROUND_USAGE,
    WALLPAPER_ACCESS,
    LOCAL_NETWORK,
    CLIPBOARD_ACCESSIBILITY
}

data class PermissionInfo(
    val title: String,
    val icon: Int,
    val description: String,
    val whyNeeded: String,
    val buttonText: String
)

@Composable
fun PermissionExplanationDialog(
    permissionType: PermissionType,
    onDismiss: () -> Unit,
    onGrantPermission: () -> Unit
) {
    val context = LocalContext.current
    val permissionInfo = getPermissionInfo(context, permissionType)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(id = permissionInfo.icon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = permissionInfo.title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Description
                Text(
                    text = permissionInfo.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Why we need this permission
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.ui_why_airsync_needs_this_permission),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                        )
                        Text(
                            text = permissionInfo.whyNeeded,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {

                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = onDismiss,
                    ) {
                        Text(context.getString(R.string.action_dismiss))
                    }

                    Button(
                        onClick = {
                            onGrantPermission()
                            onDismiss()
                        },
                    ) {
                        Text(permissionInfo.buttonText)
                    }
                }
            }
        }
    }
}

private fun getPermissionInfo(context: Context, permissionType: PermissionType): PermissionInfo {
    return when (permissionType) {
        PermissionType.NOTIFICATION_ACCESS -> PermissionInfo(
            title = context.getString(R.string.ui_notification_access),
            icon = R.drawable.rounded_notification_settings_24,
            description = context.getString(R.string.ui_airsync_needs_access_to_read_your_device_notific),
            whyNeeded = context.getString(R.string.permission_notif_access_why),
            buttonText = context.getString(R.string.ui_grant_notification_access)
        )

        PermissionType.POST_NOTIFICATIONS -> PermissionInfo(
            title = context.getString(R.string.ui_post_notifications),
            icon = R.drawable.rounded_notifications_active_24,
            description = context.getString(R.string.ui_on_android_13_apps_need_explicit_permission_to_d),
            whyNeeded = context.getString(R.string.permission_post_notif_why),
            buttonText = context.getString(R.string.ui_allow_notifications)
        )

        PermissionType.BACKGROUND_USAGE -> PermissionInfo(
            title = context.getString(R.string.ui_background_app_usage),
            icon = R.drawable.rounded_local_laundry_service_24,
            description = context.getString(R.string.ui_android_s_battery_optimization_can_kill_backgrou),
            whyNeeded = context.getString(R.string.permission_background_why),
            buttonText = context.getString(R.string.ui_disable_battery_optimization)
        )

        PermissionType.WALLPAPER_ACCESS -> PermissionInfo(
            title = context.getString(R.string.ui_wallpaper_file_access),
            icon = R.drawable.rounded_folder_managed_24,
            description = context.getString(R.string.ui_this_optional_permission_allows_airsync_to_sync_),
            whyNeeded = context.getString(R.string.permission_storage_why),
            buttonText = context.getString(R.string.ui_grant_storage_access)
        )

        PermissionType.LOCAL_NETWORK -> PermissionInfo(
            title = context.getString(R.string.permission_local_network_title),
            icon = R.drawable.rounded_sync_desktop_24,
            description = context.getString(R.string.permission_local_network_explain),
            whyNeeded = context.getString(R.string.permission_local_network_why),
            buttonText = context.getString(R.string.permission_local_network_button)
        )

        PermissionType.CLIPBOARD_ACCESSIBILITY -> PermissionInfo(
            title = context.getString(R.string.permission_clipboard_a11y_title),
            icon = R.drawable.ic_clipboard_24,
            description = context.getString(R.string.permission_clipboard_a11y_explain),
            whyNeeded = context.getString(R.string.permission_clipboard_a11y_why),
            buttonText = context.getString(R.string.permission_clipboard_a11y_button)
        )
    }
}
