package com.sameerasw.airsync.utils

import java.time.Instant

/** Position calculations share one anchor; elapsed clock and wall clock must never be mixed. */
object MediaTimeline {
    fun positionAt(
        positionMs: Long, anchorMs: Long, nowMs: Long,
        advancing: Boolean, rate: Double, durationMs: Long
    ): Long {
        val delta = if (advancing && anchorMs > 0 && nowMs >= anchorMs && rate.isFinite() && rate > 0) {
            ((nowMs - anchorMs) * rate).toLong()
        } else 0L
        val position = (positionMs.coerceAtLeast(0).toDouble() + delta).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
        return if (durationMs > 0) position.coerceIn(0, durationMs) else position
    }

    fun positionFromRemote(
        positionMs: Long, timestamp: String?, nowWallMs: Long,
        advancing: Boolean, rate: Double, durationMs: Long
    ): Long {
        val anchor = try { Instant.parse(timestamp).toEpochMilli() } catch (_: Exception) { 0L }
        return positionAt(positionMs, anchor, nowWallMs, advancing, rate, durationMs)
    }
}
