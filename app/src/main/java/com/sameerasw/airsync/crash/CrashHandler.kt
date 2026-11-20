package com.sameerasw.airsync.crash

import android.content.Context
import android.os.Build
import com.sameerasw.airsync.BuildConfig
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

object CrashHandler {
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val deviceInfo = buildString {
                    appendLine("App: AirSync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Thread: ${thread.name}")
                }
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                onCrash(appContext, deviceInfo, sw.toString())
            } catch (_: Exception) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun onCrash(context: Context, deviceInfo: String, stackTrace: String) {
        try {
            val logFile = File(context.filesDir, "last_crash.log")
            logFile.writeText("$deviceInfo\n\n$stackTrace")

            val dataStoreManager = DataStoreManager.getInstance(context)
            val notifyEnabled = runBlocking {
                dataStoreManager.getNotifyOnCrashEnabled().first()
            }

            if (notifyEnabled) {
                CrashNotificationHelper.postNotification(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
