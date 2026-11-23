package com.sameerasw.airsync

import com.sameerasw.airsync.utils.JsonUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonUtilEscapeTest {

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        val json = JsonUtil.createVolumeControlResponse(
            action = "toggleMute",
            success = true,
            message = "a\"b\\c\nd\re\tf\u0001g"
        )
        assertEquals(
            """{"type":"volumeControlResponse","data":{"action":"toggleMute","success":true,"message":"a\"b\\c\nd\re\tf\u0001g"}}""",
            json
        )
    }

    @Test
    fun escapesDeviceName() {
        val json = JsonUtil.createDeviceInfoJson(
            name = "My \"Pixel\"",
            ipAddress = "192.168.1.2",
            port = 6996,
            version = "4.1.0"
        )
        assertTrue(json.contains(""""name":"My \"Pixel\"""""))
    }
}
