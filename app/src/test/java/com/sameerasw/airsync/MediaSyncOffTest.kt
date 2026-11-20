package com.sameerasw.airsync

import com.sameerasw.airsync.domain.model.AudioInfo
import com.sameerasw.airsync.domain.model.BatteryInfo
import com.sameerasw.airsync.utils.JsonUtil
import org.junit.Assert.*
import org.junit.Test

class MediaSyncOffTest {
    private val audio = AudioInfo(true, "test-song", "test-artist", 42, false,
        "test-art", "test-lite", 100_000, 30_000, 1234, true, "liked", 1.5)

    @Test fun disablingMediaStripsEvenAnAlreadyCapturedPlayingSnapshot() {
        val json = JsonUtil.createDeviceStatusJson(BatteryInfo(76, true), audio, false)
        for (value in listOf("test-song", "test-artist", "test-art", "test-lite", "liked")) {
            assertFalse(value, json.contains(value))
        }
        assertTrue(json.contains("\"isPlaying\":false"))
        assertTrue(json.contains("\"duration\":0"))
        assertTrue(json.contains("\"position\":0"))
        assertTrue(json.contains("\"isBuffering\":false"))
        assertTrue(json.contains("\"level\":76"))
        assertTrue(json.contains("\"isCharging\":true"))
        assertTrue(json.contains("\"volume\":42"))
        assertTrue(json.contains("\"isPaired\":true"))
    }

    @Test fun enablingMediaPreservesTheCapturedTimeline() {
        val json = JsonUtil.createDeviceStatusJson(BatteryInfo(76, true), audio, true)
        assertTrue(json.contains("test-song"))
        assertTrue(json.contains("test-art"))
        assertTrue(json.contains("\"duration\":100000"))
        assertTrue(json.contains("\"position\":30000"))
        assertTrue(json.contains("\"playbackRate\":1.5"))
    }
}
