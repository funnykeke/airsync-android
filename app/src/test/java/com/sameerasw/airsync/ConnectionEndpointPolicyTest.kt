package com.sameerasw.airsync

import com.sameerasw.airsync.utils.ConnectionEndpointPolicy
import org.junit.Assert.*
import org.junit.Test

class ConnectionEndpointPolicyTest {
    @Test fun temporaryAdbEndpointPreservesEditableLanAddress() {
        val saved = "192.168.50.12"
        val editable = ConnectionEndpointPolicy.editableAddress("usb", "127.0.0.1") ?: saved
        assertEquals(saved, editable)
    }

    @Test fun relayAndDisconnectedSessionsDoNotPromoteAddresses() {
        assertNull(ConnectionEndpointPolicy.editableAddress("relay", "192.168.50.12"))
        assertNull(ConnectionEndpointPolicy.editableAddress("local", null))
    }

    @Test fun permanentLocalAddressCanStillBePromoted() {
        assertEquals("192.168.50.12", ConnectionEndpointPolicy.editableAddress("local", "192.168.50.12"))
    }

    @Test fun knownInterimLoopbackOverwriteIsRepairedFromPreservedLastLocalEndpoint() {
        assertEquals("192.168.50.12", ConnectionEndpointPolicy.recoverSavedAddress("127.0.0.1", "192.168.50.12"))
        assertEquals("fd00::123", ConnectionEndpointPolicy.recoverSavedAddress("127.0.0.1", "fd00::123"))
    }

    @Test fun recoveryPreservesDeliberateNonloopbackChoicesAndRejectsInvalidOrLoopbackHistory() {
        assertEquals("192.168.9.10", ConnectionEndpointPolicy.recoverSavedAddress("192.168.9.10", "192.168.50.12"))
        for (previous in listOf(null, "", "127.0.0.1", "127.9.8.7", "::1", "::", "0.0.0.0", "localhost", "999.1.2.3", "not-an-address")) {
            assertEquals(previous, "127.0.0.1", ConnectionEndpointPolicy.recoverSavedAddress("127.0.0.1", previous))
        }
    }
}
