package com.sameerasw.airsync.domain.model

data class MacDeviceStatus(
    val name: String = "Unknown",
    val battery: MacBattery,
    val isPaired: Boolean
)

data class MacBattery(val level: Int, val isCharging: Boolean)
