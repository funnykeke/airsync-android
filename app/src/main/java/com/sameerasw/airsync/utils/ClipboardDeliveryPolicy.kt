package com.sameerasw.airsync.utils

/** Reserves clipboard events without Android dependencies or retaining an unbounded history. */
class ClipboardDeliveryPolicy(private val maxBytes: Int = 65_536) {
    private val pending = mutableMapOf<Long, String>()
    private var revision = 0L
    private var successfulText: String? = null
    private var receivedText: String? = null
    private var observedText: String? = null

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    /** Only the latest observed clipboard value may still be delivered. */
    @Synchronized
    fun candidate(text: String, sensitive: Boolean = false): Long? {
        if (sensitive || text.isBlank() || text.length > maxBytes ||
            text.toByteArray(Charsets.UTF_8).size > maxBytes
        ) { invalidate(); return null }
        if (text == observedText &&
            (text == successfulText || text == receivedText || pending.containsValue(text))) return null
        if (text != observedText) {
            successfulText = null
            receivedText = null
        }
        observedText = text
        pending.clear()
        val token = ++revision
        pending[token] = text
        return token
    }

    @Synchronized
    fun canDeliver(token: Long): Boolean = token == revision && pending.containsKey(token)

    @Synchronized
    fun complete(token: Long, success: Boolean) {
        val text = pending.remove(token) ?: return
        // A newer candidate or remote clipboard event makes this completion stale.
        if (success && token == revision) successfulText = text
    }

    @Synchronized
    fun received(text: String) {
        ++revision
        pending.clear()
        successfulText = null
        // Large remote values cannot be candidates, so need not be retained.
        receivedText = text.takeIf { it.length <= maxBytes && it.toByteArray(Charsets.UTF_8).size <= maxBytes }
        observedText = receivedText
    }

    @Synchronized
    fun reset() {
        ++revision
        pending.clear()
        successfulText = null
        receivedText = null
        observedText = null
    }

    @Synchronized
    fun invalidate() = reset()
}
