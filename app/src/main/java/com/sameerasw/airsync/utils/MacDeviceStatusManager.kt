package com.sameerasw.airsync.utils

import android.content.Context
import com.sameerasw.airsync.domain.model.MacBattery
import com.sameerasw.airsync.domain.model.MacDeviceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Battery and identity only; no player state is collected or retained. */
object MacDeviceStatusManager {
    private val _macDeviceStatus = MutableStateFlow<MacDeviceStatus?>(null)
    val macDeviceStatus = _macDeviceStatus.asStateFlow()

    fun updateBatteryStatus(context: Context, level: Int, isCharging: Boolean) {
        val current = _macDeviceStatus.value
        updateStatus(context, current?.name ?: "Unknown", level, isCharging, current?.isPaired ?: true)
    }

    fun updateMacStatus(context: Context, name: String) {
        val current = _macDeviceStatus.value
        updateStatus(context, name, current?.battery?.level ?: -1, current?.battery?.isCharging ?: false, current?.isPaired ?: true)
    }

    fun updateStatus(context: Context, name: String, batteryLevel: Int, isCharging: Boolean, isPaired: Boolean) {
        _macDeviceStatus.value = MacDeviceStatus(name, MacBattery(batteryLevel, isCharging), isPaired)
    }

    fun startMonitoring(context: Context) = Unit
    fun stopMonitoring() = Unit
    fun cleanup(context: Context? = null) { _macDeviceStatus.value = null }
}
