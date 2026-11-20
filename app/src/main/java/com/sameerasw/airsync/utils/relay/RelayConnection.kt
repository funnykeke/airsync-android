package com.sameerasw.airsync.utils.relay

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 中转模式下的 WebSocket 客户端。
 *
 * TLS 策略：
 * - pinnedCertSha256 留空：使用 Android 系统信任链（适用于正规 CA 签发的 wss:// 域名）
 * - pinnedCertSha256 填写 64 位十六进制 SHA-256 指纹：对服务端叶证书进行指纹固定（适用于自签证书）
 */
class RelayConnection(
    private val context: Context,
    private val serverUrl: String,
    private val room: String,
    private val secret: String,
    private val pinnedCertSha256: String = "",
    private val onState: (State, String?) -> Unit,
    private val onMessage: ((String) -> Unit)?
) {
    enum class State { WAITING, PEER_ONLINE, DISCONNECTED, ERROR }

    companion object {
        private const val TAG = "RelayConnection"

        fun normalizeFingerprint(raw: String): String =
            raw.trim().replace(":", "").replace(" ", "").lowercase()

        fun isValidFingerprintOrBlank(raw: String): Boolean {
            val normalized = normalizeFingerprint(raw)
            return normalized.isEmpty() || (normalized.length == 64 && normalized.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null

    @Volatile
    private var closed = false

    @Volatile
    private var peerOnline = false

    fun connect() {
        if (closed) return

        val url = normalizedUrl()
        Log.d(TAG, "Connecting to relay: $url (room=$room)")

        val okClient = buildClient()
        if (okClient == null) {
            Log.e(TAG, "Failed to build TLS client")
            closed = true
            onState(State.ERROR, context.getString(R.string.relay_err_tls_config))
            return
        }
        client = okClient

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = okClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Relay socket opened, joining room")
                val join = JSONObject().apply {
                    put("op", "join")
                    put("room", room)
                    put("secret", secret)
                    put("role", "android")
                }
                webSocket.send(join.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!closed) withLine(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                Log.e(TAG, "Relay connection failed: ${t.message}")
                onState(State.DISCONNECTED, context.getString(R.string.relay_err_disconnected))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed) return
                Log.d(TAG, "Relay closed: $code $reason")
                onState(State.DISCONNECTED, context.getString(R.string.relay_err_disconnected))
            }
        })
    }

    fun send(text: String): Boolean = webSocket?.send(text) ?: false

    fun close() {
        closed = true
        webSocket?.close(1000, "client closing")
        webSocket = null
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        client = null
    }

    private fun withLine(text: String) {
        if (text.trimStart().startsWith("{")) {
            try {
                val obj = JSONObject(text)
                when (obj.optString("op")) {
                    "joined" -> {
                        peerOnline = obj.optJSONObject("peer")?.optBoolean("online") ?: false
                        onState(if (peerOnline) State.PEER_ONLINE else State.WAITING, null)
                    }
                    "peer" -> {
                        peerOnline = obj.optString("state") == "online"
                        onState(if (peerOnline) State.PEER_ONLINE else State.WAITING, null)
                    }
                    "pong" -> Unit
                    "error" -> {
                        val reason = obj.optString("reason")
                            .ifBlank { obj.optString("message", context.getString(R.string.relay_err_failed)) }
                        if (reason == "peer offline") {
                            peerOnline = false
                            onState(State.WAITING, null)
                        } else {
                            onState(State.ERROR, reason)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bad control frame: ${e.message}")
            }
            return
        }

        onMessage?.invoke(text)
    }

    private fun normalizedUrl(): String {
        var u = serverUrl.trim()
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) {
            u = "wss://$u"
        }
        return u
    }

    private fun buildClient(): OkHttpClient? {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)

        val expectedPin = normalizeFingerprint(pinnedCertSha256)
        if (expectedPin.isEmpty()) {
            // 使用系统信任链
            return builder.build()
        }

        if (!isValidFingerprintOrBlank(expectedPin)) {
            Log.e(TAG, "Invalid SHA-256 fingerprint format")
            return null
        }

        val expectedBytes = hexToBytes(expectedPin) ?: return null

        return try {
            val pinningTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull()
                        ?: throw CertificateException("Empty certificate chain")
                    val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    if (!MessageDigest.isEqual(actual, expectedBytes)) {
                        throw CertificateException("Server certificate SHA-256 mismatch")
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(pinningTrustManager), null)

            val pinningHostnameVerifier = HostnameVerifier { _, session ->
                try {
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: return@HostnameVerifier false
                    val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    MessageDigest.isEqual(actual, expectedBytes)
                } catch (_: Exception) {
                    false
                }
            }

            builder
                .sslSocketFactory(sslContext.socketFactory, pinningTrustManager)
                .hostnameVerifier(pinningHostnameVerifier)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build pinned TLS client: ${e.message}")
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
