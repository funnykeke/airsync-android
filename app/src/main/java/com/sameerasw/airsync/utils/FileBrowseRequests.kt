package com.sameerasw.airsync.utils

import kotlinx.coroutines.*

/** Listings run off Main; only the newest request may enqueue its captured-session response. */
class FileBrowseRequests {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var generation = 0L
    @Synchronized fun reset() {
        generation++
        scope.coroutineContext.cancelChildren()
    }
    @Synchronized fun request(owns: () -> Boolean, list: () -> String, send: (String) -> Boolean) {
        val token = ++generation
        scope.launch {
            if (!owns()) return@launch
            val response = list()
            ensureActive()
            synchronized(this@FileBrowseRequests) {
                if (token == generation && owns()) send(response)
            }
        }
    }
}
