package com.sameerasw.airsync.utils

/** Network events may retry, but only an explicit user action can release a manual stop. */
class ManualConnectionGate {
    @Volatile var stopped = false
        private set

    fun stop() { stopped = true }
    fun resume() { stopped = false }
}
