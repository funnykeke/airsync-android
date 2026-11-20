package com.sameerasw.airsync.utils

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.service.AirSyncTileService

object QuickSettingsUtil {
    fun isQSTileAdded(
        context: Context,
        serviceClass: Class<*> = AirSyncTileService::class.java
    ): Boolean {
        return try {
            val componentName = ComponentName(context, serviceClass)
            val tiles = Settings.Secure.getString(context.contentResolver, "sysui_qs_tiles") ?: ""
            tiles.contains(componentName.flattenToString()) || tiles.contains(componentName.flattenToShortString())
        } catch (_: Exception) {
            false
        }
    }

    fun requestAddQuickSettingsTile(context: Context) {
        requestAddTile(
            context,
            AirSyncTileService::class.java,
            context.getString(R.string.app_name),
            DeviceIconResolver.getLastDeviceIconRes(context)
        )
    }

    fun requestAddClipboardTile(context: Context) {
        requestAddTile(
            context,
            com.sameerasw.airsync.service.ClipboardTileService::class.java,
            "Send Clipboard",
            R.drawable.ic_clipboard_24
        )
    }

    private fun requestAddTile(
        context: Context,
        serviceClass: Class<*>,
        label: String,
        iconRes: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            val componentName = ComponentName(context, serviceClass)
            val icon = Icon.createWithResource(context, iconRes)

            statusBarManager?.requestAddTileService(
                componentName,
                label,
                icon,
                ContextCompat.getMainExecutor(context)
            ) { result ->
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                        Toast.makeText(context, context.getString(R.string.qs_tile_added, label), Toast.LENGTH_SHORT).show()
                    }

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                        Toast.makeText(
                            context,
                            context.getString(R.string.qs_tile_already_added),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {
                    }
                }
            }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.qs_tile_manual_hint, label),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

