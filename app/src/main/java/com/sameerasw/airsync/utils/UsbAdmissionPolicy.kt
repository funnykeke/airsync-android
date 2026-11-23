package com.sameerasw.airsync.utils

/** Fixed diagnostic vocabulary; no pairing material or peer identifiers. */
enum class UsbAdmissionDecision(val code: String) {
    ACCEPTED("accepted"), ALREADY_ACTIVE("already_active"),
    MANUAL_STOP("manual_stop"), AUTO_DISABLED("auto_disabled"), PAUSED("paused"),
    INVALID_PAIRING("invalid_pairing"), SUPERSEDED("superseded")
}

/** Admission order matches the USB entry point, including its existing generation fence. */
object UsbAdmissionPolicy {
    fun decide(
        stopped: Boolean, manual: Boolean, enabled: Boolean, paused: Boolean,
        paired: Boolean, active: Boolean, current: Boolean
    ): UsbAdmissionDecision = when {
        stopped || manual -> UsbAdmissionDecision.MANUAL_STOP
        !enabled -> UsbAdmissionDecision.AUTO_DISABLED
        paused -> UsbAdmissionDecision.PAUSED
        !paired -> UsbAdmissionDecision.INVALID_PAIRING
        active -> UsbAdmissionDecision.ALREADY_ACTIVE
        !current -> UsbAdmissionDecision.SUPERSEDED
        else -> UsbAdmissionDecision.ACCEPTED
    }
}
