package com.sameerasw.airsync.utils

enum class ConnectionPhase { DISCONNECTED, CONNECTING, WAITING_FOR_PEER, HANDSHAKING, CONNECTED, RECONNECTING, ERROR }
data class ConnectionSnapshot(val mode: String = "local", val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED, val error: String? = null) {
    val connected get() = phase == ConnectionPhase.CONNECTED
}
/** Tokens prevent an obsolete socket or delayed retry from modifying a newer session. */
class ConnectionLifecycle {
    @Volatile var snapshot = ConnectionSnapshot(); private set
    @Volatile var generation = 0L; private set
    fun begin(mode: String): Long {
        generation++
        snapshot = ConnectionSnapshot(mode, ConnectionPhase.CONNECTING)
        return generation
    }
    fun peer(online: Boolean) { transition(if (online) ConnectionPhase.HANDSHAKING else ConnectionPhase.WAITING_FOR_PEER) }
    fun beginHandshake(): Boolean {
        if (snapshot.phase in setOf(ConnectionPhase.HANDSHAKING, ConnectionPhase.CONNECTED)) return false
        peer(true)
        return true
    }
    fun authenticated() { if (snapshot.phase == ConnectionPhase.HANDSHAKING) transition(ConnectionPhase.CONNECTED) }
    fun transition(phase: ConnectionPhase, error: String? = null) { snapshot = snapshot.copy(phase = phase, error = error) }
    fun disconnect() { generation++; transition(ConnectionPhase.DISCONNECTED) }
    fun accepts(token: Long) = token == generation
    fun retryAllowed(token: Long, mode: String, manual: Boolean, enabled: Boolean) = accepts(token) && (snapshot.mode == mode || snapshot.mode == "usb") && !manual && enabled
}
object TransportCapabilities {
    private val relayMessages = setOf(
        "device", "macInfo", "appList", "appIcons", "notification", "notificationUpdate", "notificationAction", "dismissNotification",
        "dismissalResponse", "notificationActionResponse", "clipboardUpdate", "status",
        "volumeControl", "volumeControlResponse", "macVolume", "ping", "pong", "userDisconnected",
        "disconnectRequest", "toggleAppNotif", "toggleAppNotifResponse"
    )
    private val removedPlaybackMessages = setOf("mediaControl", "mediaControlResponse", "macMediaControl", "macMediaControlResponse", "toggleNowPlaying", "toggleNowPlayingResponse")
    fun allows(mode: String, type: String) = type !in removedPlaybackMessages && (mode in setOf("local", "usb") || type in relayMessages)
    fun validKey(value: String?): Boolean = try {
        value != null && java.util.Base64.getDecoder().decode(value.trim()).size in setOf(16, 24, 32)
    } catch (_: IllegalArgumentException) { false }
}
