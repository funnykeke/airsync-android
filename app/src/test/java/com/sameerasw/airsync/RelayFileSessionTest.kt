package com.sameerasw.airsync

import com.sameerasw.airsync.utils.*
import org.junit.Assert.*
import org.junit.Test

class RelayFileSessionTest {
    @Test fun relayAllowsOnlyExistingFileAndBrowserProtocol() {
        for (type in listOf("browseLs", "browseData", "filePull", "fileTransferInit", "fileChunk", "fileChunkAck", "fileTransferComplete", "fileTransferCancel", "transferVerified"))
            assertTrue(type, TransportCapabilities.allows("relay", type))
        for (type in listOf("startQuickShare", "remoteControl", "refreshAdbPorts", "clipboardImage", "unknown"))
            assertFalse(type, TransportCapabilities.allows("relay", type))
    }
    @Test fun rawQueuedFrameKeepsPeerProvenanceAcrossReplacement() {
        val boundary = RelayPeerBoundary()
        boundary.peer(true)
        val queuedBeforeAuthentication = boundary.capture()!!
        assertTrue(boundary.owns(queuedBeforeAuthentication))
        boundary.peer(true) // duplicate online is harmless
        assertTrue(boundary.owns(queuedBeforeAuthentication))
        boundary.peer(false)
        assertFalse(boundary.owns(queuedBeforeAuthentication))
        boundary.peer(true)
        assertFalse(boundary.owns(queuedBeforeAuthentication))
        assertTrue(boundary.owns(boundary.capture()!!))
    }
    @Test fun capturedSenderCannotRetargetAfterPeerReplacement() {
        val boundary = RelayPeerBoundary()
        boundary.peer(true)
        val token = boundary.capture()!!
        var sends = 0
        assertTrue(boundary.send(token) { sends++; true })
        boundary.peer(false); boundary.peer(true)
        assertFalse(boundary.send(token) { sends++; true })
        assertEquals(1, sends)
    }
}
