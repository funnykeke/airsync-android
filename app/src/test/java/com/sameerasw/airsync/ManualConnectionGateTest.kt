package com.sameerasw.airsync

import com.sameerasw.airsync.utils.ConnectionLifecycle
import com.sameerasw.airsync.utils.ManualConnectionGate
import org.junit.Assert.*
import org.junit.Test

class ManualConnectionGateTest {
    @Test fun explicitResumeUnblocksReconnectAfterPause() {
        val gate = ManualConnectionGate()
        val lifecycle = ConnectionLifecycle()
        val token = lifecycle.begin("relay")
        gate.stop()
        repeat(3) {
            assertFalse(lifecycle.retryAllowed(token, "relay", gate.stopped, true))
        }
        gate.resume()
        assertTrue(lifecycle.retryAllowed(token, "relay", gate.stopped, true))
    }

    @Test fun resumeCannotReviveAnObsoleteSocketOrOverrideDisabledAutoConnect() {
        val gate = ManualConnectionGate()
        val lifecycle = ConnectionLifecycle()
        val token = lifecycle.begin("usb")
        gate.stop()
        lifecycle.disconnect()
        gate.resume()
        assertFalse(lifecycle.retryAllowed(token, "relay", gate.stopped, true))
        val current = lifecycle.begin("relay")
        assertFalse(lifecycle.retryAllowed(current, "relay", gate.stopped, false))
        assertTrue(lifecycle.retryAllowed(current, "relay", gate.stopped, true))
    }
}
