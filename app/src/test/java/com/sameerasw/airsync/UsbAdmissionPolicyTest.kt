package com.sameerasw.airsync

import com.sameerasw.airsync.utils.UsbAdmissionDecision
import com.sameerasw.airsync.utils.UsbAdmissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAdmissionPolicyTest {
    private fun decide(
        stopped: Boolean = false, manual: Boolean = false,
        enabled: Boolean = true, paused: Boolean = false,
        paired: Boolean = true, active: Boolean = false, current: Boolean = true
    ) = UsbAdmissionPolicy.decide(stopped, manual, enabled, paused, paired, active, current)

    @Test fun eligiblePairedRequestCanStartUsb() {
        assertEquals(UsbAdmissionDecision.ACCEPTED, decide())
    }
    @Test fun eitherManualStopFenceRejectsEvenAnActiveUsbSession() {
        assertEquals(UsbAdmissionDecision.MANUAL_STOP, decide(stopped = true, active = true))
        assertEquals(UsbAdmissionDecision.MANUAL_STOP, decide(manual = true, active = true))
    }
    @Test fun disabledAutoReconnectHasItsOwnReason() {
        assertEquals(UsbAdmissionDecision.AUTO_DISABLED, decide(enabled = false))
    }
    @Test fun pauseHasItsOwnReason() {
        assertEquals(UsbAdmissionDecision.PAUSED, decide(paused = true))
    }
    @Test fun missingOrInvalidPairingCannotBeBypassedByExistingUsb() {
        assertEquals(UsbAdmissionDecision.INVALID_PAIRING, decide(paired = false, active = true))
    }
    @Test fun staleRequestCannotReplaceNewerNetworkSession() {
        assertEquals(UsbAdmissionDecision.SUPERSEDED, decide(current = false))
    }
    @Test fun alreadyActiveUsbWinsOverStaleRequestWithoutStartingAnotherSession() {
        assertEquals(UsbAdmissionDecision.ALREADY_ACTIVE, decide(active = true, current = false))
    }
    @Test fun rejectionPriorityPreservesUserIntentBeforePairingAndGeneration() {
        assertEquals(UsbAdmissionDecision.MANUAL_STOP,
            decide(stopped = true, enabled = false, paused = true, paired = false, current = false))
        assertEquals(UsbAdmissionDecision.AUTO_DISABLED,
            decide(enabled = false, paused = true, paired = false, current = false))
        assertEquals(UsbAdmissionDecision.PAUSED,
            decide(paused = true, paired = false, current = false))
        assertEquals(UsbAdmissionDecision.INVALID_PAIRING, decide(paired = false, current = false))
    }
}
