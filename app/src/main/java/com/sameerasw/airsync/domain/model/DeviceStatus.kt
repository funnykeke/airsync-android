package com.sameerasw.airsync.domain.model

data class BatteryInfo(val level: Int, val isCharging: Boolean)

/** Device output volume, independent of any player or media session. */
data class SystemVolume(val level: Int, val isMuted: Boolean)
