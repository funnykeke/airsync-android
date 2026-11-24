package com.sameerasw.airsync.utils

/** Raw OkHttp callback fence: provenance exists before Main finishes authentication. */
class RelayPeerBoundary {
    private var generation = 0L
    private var online = false
    @Synchronized fun peer(value: Boolean) {
        if (online != value) { generation++; online = value }
    }
    @Synchronized fun capture(): Long? = generation.takeIf { online }
    @Synchronized fun owns(token: Long) = online && token == generation
    /** Keep the check and socket enqueue atomic with peer-offline callbacks. */
    @Synchronized fun send(token: Long, enqueue: () -> Boolean) = owns(token) && enqueue()
}
