package com.sameerasw.airsync.utils

import com.sameerasw.airsync.utils.discovery.DiscoverySource

data class DiscoveredDevice(
    val id: String,
    val name: String,
    val ips: Set<String>,
    val port: Int,
    val type: String, // "mac" or "android"
    val lastSeen: Long = System.currentTimeMillis(),
    val discoverySource: DiscoverySource = DiscoverySource.UDP
) {
    fun hasLocalIp(): Boolean = ips.any { !it.startsWith("100.") }

    fun hasTailscaleIp(): Boolean = ips.any { it.startsWith("100.") }

    fun getBestIp(): String = ips.find { !it.startsWith("100.") } ?: ips.firstOrNull() ?: ""
}

enum class DiscoveryMode {
    ACTIVE,  // Continuous broadcasting (Foreground)
    PASSIVE  // Listening only (Background)
}
