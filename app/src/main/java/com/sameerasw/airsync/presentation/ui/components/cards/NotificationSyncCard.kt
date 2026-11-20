package com.sameerasw.airsync.presentation.ui.components.cards

import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sameerasw.airsync.R

@Composable
fun NotificationSyncCard(
    isNotificationEnabled: Boolean,
    isNotificationSyncEnabled: Boolean,
    onToggleSync: (Boolean) -> Unit,
    onGrantPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconToggleItem(
        modifier = modifier,
        iconRes = R.drawable.rounded_notifications_active_24,
        title = stringResource(R.string.ui_notification_sync),
        description = if (!isNotificationEnabled) stringResource(R.string.notif_access_required) else null,
        isChecked = isNotificationSyncEnabled && isNotificationEnabled,
        onCheckedChange = { enabled ->
            if (isNotificationEnabled) {
                onToggleSync(enabled)
            } else {
                onGrantPermissions()
            }
        },
        enabled = isNotificationEnabled,
        onDisabledClick = onGrantPermissions
    )
}