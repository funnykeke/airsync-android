package com.sameerasw.airsync.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class ClipboardTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "ClipboardTileService"
    }

    // Keep a reference to unregister properly
    private val connectionStatusListener: (Boolean) -> Unit = { status ->
        serviceScope.launch {
            updateTileState(status)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Register for connection status updates
        WebSocketUtil.registerConnectionStatusListener(connectionStatusListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        WebSocketUtil.unregisterConnectionStatusListener(connectionStatusListener)
        serviceScope.cancel()
    }

    override fun onStartListening() {
        super.onStartListening()
        serviceScope.launch {
            val isConnected = WebSocketUtil.isConnected()
            updateTileState(isConnected)
        }
    }

    override fun onClick() {
        super.onClick()

        serviceScope.launch {
            val dataStoreManager = DataStoreManager.getInstance(this@ClipboardTileService)
            val isPaused = dataStoreManager.isAppPaused().first()
            if (isPaused) {
                val mainIntent = Intent(this@ClipboardTileService, com.sameerasw.airsync.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                launchActivityAndCollapse(mainIntent)
                return@launch
            }

            val isConnected = WebSocketUtil.isConnected()
            if (isConnected) {
                try {
                    val intent = Intent(
                        this@ClipboardTileService,
                        com.sameerasw.airsync.presentation.ui.activities.ClipboardActionActivity::class.java
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launchActivityAndCollapse(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching clipboard activity", e)
                    Toast.makeText(
                        this@ClipboardTileService,
                        getString(R.string.toast_error_launching_action),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(
                    this@ClipboardTileService,
                    getString(R.string.no_device_connected),
                    Toast.LENGTH_SHORT
                ).show()
                updateTileState(false)
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchActivityAndCollapse(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // The PendingIntent overload was added in API 34.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    // Removed direct sendClipboard method as it is now handled by the Activity

    private fun updateTileState(isConnected: Boolean) {
        serviceScope.launch {
            try {
                val dataStoreManager = DataStoreManager.getInstance(this@ClipboardTileService)
                val isPaused = dataStoreManager.isAppPaused().first()
                val connectedDevice = dataStoreManager.getLastConnectedDevice().first()
                val deviceName = connectedDevice?.name ?: getString(R.string.your_mac)

                qsTile?.apply {
                    state = if (isConnected && !isPaused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        subtitle = when {
                            isPaused -> getString(R.string.paused)
                            isConnected -> deviceName
                            else -> getString(R.string.disconnected)
                        }
                    } else {
                        val labelText = when {
                            isPaused -> "Clipboard (Paused)"
                            isConnected -> "Send Clipboard ($deviceName)"
                            else -> "Not Connected"
                        }
                        label = labelText
                    }

                    updateTile()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating tile state", e)
            }
        }
    }
}
