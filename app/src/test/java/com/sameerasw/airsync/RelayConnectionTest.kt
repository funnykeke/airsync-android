package com.sameerasw.airsync

import com.sameerasw.airsync.utils.relay.RelayConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConnectionTest {

    @Test
    fun blankFingerprintIsAllowedForSystemTrustStore() {
        assertTrue(RelayConnection.isValidFingerprintOrBlank(""))
        assertTrue(RelayConnection.isValidFingerprintOrBlank("   "))
        assertEquals("", RelayConnection.normalizeFingerprint("  "))
    }

    @Test
    fun validSha256FingerprintsNormalizeAndPass() {
        val raw = "A1:B2:C3:D4:E5:F6:01:23:45:67:89:AB:CD:EF:00:11:" +
            "22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:12:34"
        val normalized = RelayConnection.normalizeFingerprint(" $raw ")
        assertEquals(64, normalized.length)
        assertEquals(
            "a1b2c3d4e5f60123456789abcdef00112233445566778899aabbccddeeff1234",
            normalized
        )
        assertTrue(RelayConnection.isValidFingerprintOrBlank(raw))
        assertTrue(RelayConnection.isValidFingerprintOrBlank(normalized))
    }

    @Test
    fun invalidFingerprintsAreRejected() {
        assertFalse(RelayConnection.isValidFingerprintOrBlank("abcd"))
        assertFalse(RelayConnection.isValidFingerprintOrBlank("g".repeat(64)))
        assertFalse(RelayConnection.isValidFingerprintOrBlank("a".repeat(63)))
        assertFalse(RelayConnection.isValidFingerprintOrBlank("a".repeat(65)))
    }
}
