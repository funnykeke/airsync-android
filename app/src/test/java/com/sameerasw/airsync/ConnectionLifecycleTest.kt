package com.sameerasw.airsync

import com.sameerasw.airsync.utils.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionLifecycleTest {
    @Test fun replacementPeerRequiresFreshHandshakeWhileAlreadyConnected() {
        val connection = ConnectionLifecycle()
        connection.begin("relay")
        assertTrue(connection.beginHandshake())
        assertFalse(connection.beginHandshake())
        connection.authenticated()
        assertTrue(connection.snapshot.connected)
        assertTrue(connection.beginHandshake())
        assertEquals(ConnectionPhase.HANDSHAKING, connection.snapshot.phase)
        assertFalse(connection.snapshot.connected)
    }
    @Test fun relayWaitsWithoutPretendingToBeConnected() {
        val connection = ConnectionLifecycle()
        connection.begin("relay")
        connection.peer(false)
        assertEquals(ConnectionPhase.WAITING_FOR_PEER, connection.snapshot.phase)
        connection.peer(true)
        assertEquals(ConnectionPhase.HANDSHAKING, connection.snapshot.phase)
        assertFalse(connection.snapshot.connected)
        connection.authenticated()
        assertTrue(connection.snapshot.connected)
    }
    @Test fun disconnectAndModeSwitchInvalidateOldCallbacks() {
        val connection = ConnectionLifecycle()
        val first = connection.begin("relay")
        connection.disconnect()
        assertFalse(connection.accepts(first))
        val second = connection.begin("relay")
        connection.begin("local")
        assertFalse(connection.accepts(second))
    }
    @Test fun reconnectRequiresCurrentModeAndExplicitAutoSetting() {
        val c = ConnectionLifecycle()
        val token = c.begin("relay")
        assertFalse(c.retryAllowed(token, "local", false, true))
        assertFalse(c.retryAllowed(token, "relay", true, true))
        assertFalse(c.retryAllowed(token, "relay", false, false))
        assertTrue(c.retryAllowed(token, "relay", false, true))
    }
    @Test fun relayAcceptsMediaButRejectsPrivilegedAndUnknownMessages() {
        for (type in listOf("device", "macInfo", "appList", "appIcons", "notification", "notificationAction", "dismissNotification", "clipboardUpdate", "status", "macMediaControl", "macMediaControlResponse", "mediaControl")) assertTrue(type, TransportCapabilities.allows("relay", type))
        for (type in listOf("remoteControl", "browseLs", "startQuickShare", "clipboardImage", "fileChunk", "unknown")) assertFalse(type, TransportCapabilities.allows("relay", type))
        assertTrue(TransportCapabilities.allows("local", "remoteControl"))
    }
    @Test fun encryptionKeyMustBeValidAesMaterial() {
        assertFalse(TransportCapabilities.validKey(null))
        assertFalse(TransportCapabilities.validKey(""))
        assertFalse(TransportCapabilities.validKey("not a key"))
        assertFalse(TransportCapabilities.validKey("YWJj"))
        assertTrue(TransportCapabilities.validKey(java.util.Base64.getEncoder().encodeToString(ByteArray(32))))
    }
}
