package com.sameerasw.airsync.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.presentation.ui.activities.ClipboardActionActivity
import com.sameerasw.airsync.presentation.ui.activities.QuickShareSendActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object ShortcutUtil {
    const val SHORTCUT_ID_SCAN = "shortcut_scan"
    const val SHORTCUT_ID_RECONNECT = "shortcut_reconnect"
    const val SHORTCUT_ID_CLIPBOARD = "shortcut_clipboard"
    const val SHORTCUT_ID_LOCK = "shortcut_lock"
    const val SHORTCUT_ID_REMOTE = "shortcut_remote"
    const val SHORTCUT_ID_DISCONNECT = "shortcut_disconnect"
    const val SHORTCUT_ID_PAUSE = "shortcut_pause"
    const val SHORTCUT_ID_RESUME = "shortcut_resume"
    const val SHORTCUT_ID_SHARE_MAC = "shortcut_share_mac"
    const val SHARE_TARGET_CATEGORY_FILE = "com.sameerasw.airsync.categories.SHARE_TARGET_FILE"

    const val DASH_ACTION_RECONNECT = "com.sameerasw.airsync.ACTION_RECONNECT"
    const val DASH_ACTION_CLIPBOARD = "com.sameerasw.airsync.ACTION_CLIPBOARD"
    const val DASH_ACTION_LOCK = "com.sameerasw.airsync.ACTION_LOCK"
    const val DASH_ACTION_REMOTE = "com.sameerasw.airsync.ACTION_REMOTE"
    const val DASH_ACTION_DISCONNECT = "com.sameerasw.airsync.ACTION_DISCONNECT"
    const val DASH_ACTION_PAUSE = "com.sameerasw.airsync.ACTION_PAUSE"
    const val DASH_ACTION_RESUME = "com.sameerasw.airsync.ACTION_RESUME"

    fun refreshShortcuts(context: Context, isConnected: Boolean) {
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        val dataStoreManager = DataStoreManager.getInstance(context)
        val isPaused = runBlocking {
            dataStoreManager.isAppPaused().first()
        }

        if (isPaused) {
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_RESUME)
                    .setShortLabel(context.getString(R.string.resume))
                    .setLongLabel(context.getString(R.string.shortcut_resume_airsync))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_play_arrow_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_RESUME
                    })
                    .build()
            )
        } else if (!isConnected) {
            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_SCAN)
                    .setShortLabel(context.getString(R.string.shortcut_scan_short))
                    .setLongLabel(context.getString(R.string.scan_qr_code))
                    .setIcon(
                        IconCompat.createWithResource(
                            context,
                            R.drawable.rounded_qr_code_scanner_24
                        )
                    )
                    .setIntent(
                        Intent(
                            context,
                            com.sameerasw.airsync.presentation.ui.activities.QRScannerActivity::class.java
                        ).apply {
                            action = "com.sameerasw.airsync.SCAN_QR"
                        })
                    .build()
            )

            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_RECONNECT)
                    .setShortLabel(context.getString(R.string.ui_reconnect))
                    .setLongLabel(context.getString(R.string.ui_reconnect))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_devices_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_RECONNECT
                    })
                    .build()
            )

            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_PAUSE)
                    .setShortLabel(context.getString(R.string.pause))
                    .setLongLabel(context.getString(R.string.shortcut_pause_airsync))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_pause_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_PAUSE
                    })
                    .build()
            )
        } else {
            buildShareMacShortcut(context)?.let { shortcuts.add(it) }

            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_REMOTE)
                    .setShortLabel(context.getString(R.string.tab_remote))
                    .setLongLabel(context.getString(R.string.shortcut_remote_control))
                    .setIcon(
                        IconCompat.createWithResource(
                            context,
                            R.drawable.rounded_compare_arrows_24
                        )
                    )
                    .setIntent(
                        Intent(
                            context,
                            com.sameerasw.airsync.MainActivity::class.java
                        ).apply {
                            action = DASH_ACTION_REMOTE
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })
                    .build()
            )

            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_CLIPBOARD)
                    .setShortLabel(context.getString(R.string.tab_clipboard))
                    .setLongLabel(context.getString(R.string.sync_clipboard))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_clipboard_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_CLIPBOARD
                    })
                    .build()
            )

            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_LOCK)
                    .setShortLabel(context.getString(R.string.action_lock_screen))
                    .setLongLabel(context.getString(R.string.shortcut_lock_mac))
                    .setIcon(IconCompat.createWithResource(context, R.drawable.rounded_lock_24))
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_LOCK
                    })
                    .build()
            )

            shortcuts.add(
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID_DISCONNECT)
                    .setShortLabel(context.getString(R.string.disconnect))
                    .setLongLabel(context.getString(R.string.disconnect))
                    .setIcon(
                        IconCompat.createWithResource(
                            context,
                            R.drawable.rounded_mimo_disconnect_24
                        )
                    )
                    .setIntent(Intent(context, ClipboardActionActivity::class.java).apply {
                        action = DASH_ACTION_DISCONNECT
                    })
                    .build()
            )
        }

        // Set dynamic shortcuts (replaces existing ones)
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }


    private fun buildShareMacShortcut(context: Context): ShortcutInfoCompat? {
        val dataStoreManager = DataStoreManager.getInstance(context)
        val lastConnectedDevice = runBlocking {
            dataStoreManager.getLastConnectedDevice().first()
        } ?: return null

        return ShortcutInfoCompat.Builder(context, SHORTCUT_ID_SHARE_MAC)
            .setShortLabel(lastConnectedDevice.name)
            .setLongLabel(lastConnectedDevice.name)
            .setIcon(IconCompat.createWithResource(context, MacModelMapper.getShareIconRes(lastConnectedDevice)))
            .setLongLived(true)
            .setCategories(setOf(SHARE_TARGET_CATEGORY_FILE))
            .setIntent(Intent(context, QuickShareSendActivity::class.java).apply {
                action = Intent.ACTION_SEND
            })
            .build()
    }


    fun reportShareMacShortcutUsed(context: Context) {
        buildShareMacShortcut(context)?.let {
            ShortcutManagerCompat.pushDynamicShortcut(context, it)
        }
    }

    // Legacy method cleanup
    fun removeShareShortcut(context: Context) {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }
}
