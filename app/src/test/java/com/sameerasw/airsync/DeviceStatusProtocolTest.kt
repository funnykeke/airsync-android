package com.sameerasw.airsync

import com.sameerasw.airsync.domain.model.BatteryInfo
import com.sameerasw.airsync.domain.model.SystemVolume
import com.sameerasw.airsync.utils.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class DeviceStatusProtocolTest {
    @Test fun statusContainsOnlyBatteryPairingAndIndependentVolume() {
        assertEquals(
            """{"type":"status","data":{"battery":{"level":76,"isCharging":true},"isPaired":true,"volume":{"level":42,"isMuted":false}}}""",
            JsonUtil.createDeviceStatusJson(BatteryInfo(76, true), SystemVolume(42, false))
        )
    }

    @Test fun mutedAndBoundaryVolumeRemainValidWithoutAPlayer() {
        assertTrue(JsonUtil.createDeviceStatusJson(BatteryInfo(0, false), SystemVolume(-1, true))
            .contains(""""volume":{"level":0,"isMuted":true}"""))
        assertTrue(JsonUtil.createDeviceStatusJson(BatteryInfo(100, false), SystemVolume(101, false))
            .contains(""""volume":{"level":100,"isMuted":false}"""))
    }
}
