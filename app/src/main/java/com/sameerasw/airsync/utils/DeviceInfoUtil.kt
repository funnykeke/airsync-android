package com.sameerasw.airsync.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.sameerasw.airsync.domain.model.SystemVolume
import com.sameerasw.airsync.domain.model.BatteryInfo
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID

object DeviceInfoUtil {

    fun getDeviceName(context: Context): String {
        return try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                ?: Build.MODEL
                ?: "Android Device"
        } catch (_: Exception) {
            Build.MODEL ?: "Android Device"
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress) {
                        val hostAddress = address.hostAddress
                        if (hostAddress != null && hostAddress.contains(".") && !hostAddress.contains(
                                ":"
                            )
                        ) {
                            // IPv4 address
                            return hostAddress
                        }
                    }
                }
            }
        } catch (_: Exception) {

        }
        return null
    }

    fun getWifiIpAddress(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Use ConnectivityManager and LinkProperties for Android 12+
                val connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (networkCapabilities != null &&
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                ) {
                    val linkProperties: LinkProperties? =
                        connectivityManager.getLinkProperties(activeNetwork)
                    linkProperties?.linkAddresses?.forEach { linkAddress ->
                        val address = linkAddress.address
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress
                        }
                    }
                }
                // Fallback to local IP if not found
                getLocalIpAddress()
            } else {
                // Deprecated method for older versions
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                @Suppress("DEPRECATION")
                val wifiInfo = wifiManager.connectionInfo

                @Suppress("DEPRECATION")
                val ipAddress = wifiInfo.ipAddress
                if (ipAddress != 0) {
                    String.format(
                        Locale.US,
                        "%d.%d.%d.%d",
                        ipAddress and 0xff,
                        ipAddress shr 8 and 0xff,
                        ipAddress shr 16 and 0xff,
                        ipAddress shr 24 and 0xff
                    )
                } else {
                    getLocalIpAddress()
                }
            }
        } catch (_: Exception) {
            getLocalIpAddress()
        }
    }

    fun getSystemVolume(context: Context): SystemVolume {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return SystemVolume(if (maximum > 0) (current * 100 / maximum).coerceIn(0, 100) else 0, current == 0)
    }
    fun getBatteryInfo(context: Context): BatteryInfo {
        return try {
            val batteryIntent =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val batteryPercent = if (level != -1 && scale != -1) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                0
            }

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            BatteryInfo(batteryPercent, isCharging)
        } catch (_: Exception) {
            BatteryInfo(0, false)
        }
    }


    fun generateDeviceStatusJson(context: Context): String =
        JsonUtil.createDeviceStatusJson(getBatteryInfo(context), getSystemVolume(context))


    fun isBlurProblematicDevice(): Boolean {
        // Samsung devices on One UI 7 (Android 15) or below have a broken blur implementation
        // that causes a gray screen overlay. Disable it for them. (╯°□°）╯︵ ┻━┻
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                Build.VERSION.SDK_INT <= 35 // Android 15
    }

    fun getDeviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
        } catch (_: Exception) {
            UUID.randomUUID().toString()
        }
    }

    fun isPowerSaveMode(context: Context): Boolean {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return powerManager?.isPowerSaveMode == true
    }
}
