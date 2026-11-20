package com.sameerasw.airsync.presentation.ui.components.cards

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.ClipboardSyncManager
import com.sameerasw.airsync.utils.RootClipboardMonitor
import com.sameerasw.airsync.utils.RootClipboardPhase
import kotlinx.coroutines.launch

@Composable
fun RootClipboardSettings(context: Context, clipboardEnabled: Boolean, onStatusChanged: () -> Unit) {
    val settings = remember(context) { DataStoreManager.getInstance(context) }
    val rootEnabled by remember(settings) { settings.getRootClipboardEnabled() }.collectAsState(false)
    val phase by RootClipboardMonitor.phase.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(rootEnabled, phase) { onStatusChanged() }
    IconToggleItem(
        title = stringResource(R.string.root_clipboard_title),
        description = stringResource(R.string.root_clipboard_description),
        iconRes = R.drawable.ic_clipboard_24,
        isChecked = rootEnabled,
        enabled = clipboardEnabled,
        onCheckedChange = { enabled -> scope.launch { settings.setRootClipboardEnabled(enabled) } }
    )
    if (rootEnabled) {
        val status = when {
            !clipboardEnabled -> R.string.root_clipboard_disabled
            phase == RootClipboardPhase.STARTING -> R.string.root_clipboard_starting
            phase == RootClipboardPhase.LISTENING -> R.string.root_clipboard_listening
            phase == RootClipboardPhase.RETRYING -> R.string.root_clipboard_recovering
            phase == RootClipboardPhase.ERROR -> R.string.root_clipboard_error
            else -> R.string.root_clipboard_waiting
        }
        IconToggleItem(
            title = stringResource(status),
            description = if (phase == RootClipboardPhase.ERROR) stringResource(R.string.root_clipboard_retry) else null,
            showToggle = false,
            onClick = if (clipboardEnabled && phase == RootClipboardPhase.ERROR) {
                { ClipboardSyncManager.retryRoot(context) }
            } else null
        )
    }
}
