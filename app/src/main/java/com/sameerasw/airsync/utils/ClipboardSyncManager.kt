package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel

object ClipboardSyncManager {
    private const val TAG = "ClipboardSyncManager"

    @Volatile private var isEnabled = false
    @Volatile var isRootSelected = false
        private set
    private val delivery = ClipboardDeliveryPolicy()
    private val syncScope = CoroutineScope(Dispatchers.IO)
    private data class PendingClipboard(val text: String, val session: Any, val token: Long)
    private val events = Channel<PendingClipboard>(Channel.CONFLATED)

    init {
        syncScope.launch {
            for (event in events) deliver(event)
        }
    }

    // Callback for clipboard history tracking
    private var onClipboardSent: ((text: String) -> Unit)? = null

    fun setOnClipboardSentCallback(callback: ((text: String) -> Unit)?) {
        onClipboardSent = callback
    }

    /** Driven by AirSyncService, including when no Compose screen exists. */
    fun configure(context: Context, enabled: Boolean, root: Boolean) {
        if (isRootSelected != root) RootClipboardMonitor.clearFailure()
        if (isEnabled != enabled || isRootSelected != root) delivery.reset()
        isRootSelected = root
        isEnabled = enabled
        if (enabled && root) startRoot(context)
        else RootClipboardMonitor.stop()
    }

    fun resetSession() = delivery.reset()

    fun retryRoot(context: Context) {
        if (!isEnabled || !isRootSelected) return
        RootClipboardMonitor.stop()
        RootClipboardMonitor.clearFailure()
        startRoot(context)
    }

    private fun startRoot(context: Context) = RootClipboardMonitor.start(context,
        onInvalidated = { if (isRootSelected) delivery.invalidate() },
        onText = { if (isRootSelected) sendCaptured(it) })

    fun shutdown() {
        isEnabled = false
        delivery.reset()
        RootClipboardMonitor.stop()
    }

    private fun sendCaptured(text: String) {
        if (!isEnabled) return
        val session = WebSocketUtil.clipboardSessionToken() ?: return
        val token = delivery.candidate(text) ?: return
        events.trySend(PendingClipboard(text, session, token))
    }

    private suspend fun deliver(event: PendingClipboard) {
        val (text, session, token) = event
            var success = false
            try {
                val clipboardJson = JsonUtil.createClipboardUpdateJson(text)
                // Session changes and sending are serialized with the transport's main scope.
                for (attempt in 0..2) {
                    success = withContext(Dispatchers.Main.immediate) {
                        isEnabled && delivery.canDeliver(token) &&
                            WebSocketUtil.sendClipboardMessage(session, clipboardJson)
                    }
                    if (success || !isEnabled || !delivery.canDeliver(token) ||
                        session !== WebSocketUtil.clipboardSessionToken()) break
                    // Retry only this event's send, never poll the system clipboard.
                    if (attempt < 2) delay(250L * (attempt + 1))
                }
                if (success) {
                    Log.d(TAG, "Clipboard synced to desktop")
                    onClipboardSent?.invoke(text)
                } else {
                    Log.w(TAG, "Failed to sync clipboard to desktop")
                }
            } catch (_: Exception) {
                Log.w(TAG, "Clipboard delivery failed")
            } finally {
                delivery.complete(token, success)
            }
    }

    private fun isLinkOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Split by whitespace to check if it's only a single token (no other text)
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size != 1) return false

        val url = tokens[0]

        // Check for explicit protocols
        if (url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true) ||
            url.startsWith("ftp://", ignoreCase = true)
        ) {
            return true
        }

        // Check for www prefix without protocol
        if (url.startsWith("www.", ignoreCase = true)) {
            return isValidDomainFormat(url.substring(4))
        }

        // Check if it looks like a domain name (no protocol, no www)
        return isValidDomainFormat(url)
    }

    private fun isValidDomainFormat(domain: String): Boolean {
        if (domain.isEmpty()) return false

        // Basic domain validation regex
        // Matches: domain.com, sub.domain.com, domain.co.uk, etc.
        val domainRegex = Regex(
            "^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?" +
                    "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*" +
                    "\\.[a-zA-Z]{2,}$",
            RegexOption.IGNORE_CASE
        )

        return domainRegex.matches(domain)
    }

    /**
     * Handle clipboard update received from desktop
     */
    fun handleClipboardUpdate(context: Context, text: String) {
        try {
            delivery.received(text)

            // Always update clipboard with the full received text
            val updated = ClipboardUtil.setClipboardText(context, text)
            if (updated) {
                Log.d(TAG, "Clipboard updated from desktop")
            } else {
                Log.w(TAG, "Failed to update clipboard from desktop")
            }

            // Check Continue Browsing setting and show notification only if the text is a pure link
            val dataStoreManager = DataStoreManager(context)
            syncScope.launch {
                val continueEnabled = try {
                    dataStoreManager.getContinueBrowsingEnabled().first()
                } catch (_: Exception) {
                    true
                }
                val keepPrevious = try {
                    dataStoreManager.getKeepPreviousLinkEnabled().first()
                } catch (_: Exception) {
                    true
                }
                if (continueEnabled && WebSocketUtil.isConnected() && isLinkOnly(text)) {
                    NotificationUtil.showContinueBrowsingLink(context, text.trim(), keepPrevious)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard update: ${e.message}")
        }
    }

    /**
     * Called by ClipboardGhostActivity when ClipboardAccessibilityService detects a copy
     */
    fun onBackgroundClipboardCaptured(context: Context, text: String) {
        if (!isRootSelected) sendCaptured(text)
    }

    /**
     * Manually sync specific text (share target)
     */
    fun syncTextToDesktop(text: String) {
        if (text.isBlank()) return

        try {
            val clipboardJson = JsonUtil.createClipboardUpdateJson(text)

            val success = WebSocketUtil.sendMessage(clipboardJson)
            if (success) {
                Log.d(TAG, "Text shared to desktop")
                // Notify callback about sent clipboard
                onClipboardSent?.invoke(text)
            } else {
                Log.w(TAG, "Failed to share text to desktop")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing text to desktop: ${e.message}")
        }
    }
}
