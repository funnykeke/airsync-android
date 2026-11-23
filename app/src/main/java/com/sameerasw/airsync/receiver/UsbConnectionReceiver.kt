package com.sameerasw.airsync.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sameerasw.airsync.utils.WebSocketUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Explicit, shell-only ADB entry point; sender permission is enforced by the manifest. */
class UsbConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CONNECT && intent.action != DISCONNECT) return
        val pending = goAsync()
        scope.launch {
            try {
                pending.resultCode = if (intent.action == CONNECT) {
                    if (WebSocketUtil.connectUsb(context.applicationContext)) Activity.RESULT_OK else Activity.RESULT_CANCELED
                } else {
                    WebSocketUtil.disconnectUsb(context.applicationContext)
                    Activity.RESULT_OK
                }
            } catch (_: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val CONNECT = "com.sameerasw.airsync.USB_CONNECT"
        const val DISCONNECT = "com.sameerasw.airsync.USB_DISCONNECT"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
