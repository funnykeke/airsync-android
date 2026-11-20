package com.sameerasw.airsync

import com.sameerasw.airsync.utils.MediaTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTimelineTest {
    @Test fun androidPlaybackStateIsProjectedFromItsMonotonicUpdateTime() {
        assertEquals(72_000L, MediaTimeline.positionAt(12_000, 40_000, 100_000, true, 1.0, 200_000))
    }
    @Test fun pausedAndBufferingTimelineDoesNotAdvance() {
        assertEquals(12_000L, MediaTimeline.positionAt(12_000, 40_000, 100_000, false, 1.0, 200_000))
    }
    @Test fun speedAndDurationAreAppliedToAnAbsoluteAnchor() {
        assertEquals(132_000L, MediaTimeline.positionAt(12_000, 40_000, 100_000, true, 2.0, 200_000))
        assertEquals(50_000L, MediaTimeline.positionAt(12_000, 40_000, 100_000, true, 2.0, 50_000))
    }
    @Test fun macTimestampIsConsumedOnceThenUiUsesMonotonicTime() {
        val received = MediaTimeline.positionFromRemote(0, "2026-11-18T09:20:55Z", 1794993727000, true, 1.0, 200_000)
        assertEquals(72_000L, received)
        assertEquals(74_000L, MediaTimeline.positionAt(received, 1_000, 3_000, true, 1.0, 200_000))
    }
    @Test fun seekBackwardAndTrackChangeReplaceAnchorImmediately() {
        assertEquals(5_000L, MediaTimeline.positionAt(3_000, 1_000, 3_000, true, 1.0, 200_000))
        assertEquals(2_000L, MediaTimeline.positionAt(0, 1_000, 3_000, true, 1.0, 200_000))
    }
    @Test fun invalidFutureOrAbsentTimestampsDoNotInventProgress() {
        assertEquals(5_000L, MediaTimeline.positionFromRemote(5_000, "invalid", 1_000, true, 1.0, 200_000))
        assertEquals(5_000L, MediaTimeline.positionAt(5_000, 0, 3_000, true, 1.0, 200_000))
        assertEquals(5_000L, MediaTimeline.positionAt(5_000, 4_000, 3_000, true, 1.0, 200_000))
    }
}
