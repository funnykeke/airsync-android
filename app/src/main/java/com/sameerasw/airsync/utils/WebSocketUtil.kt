package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Log
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.service.AirSyncService
import com.sameerasw.airsync.service.MediaNotificationListener
import com.sameerasw.airsync.utils.relay.RelayConnection
import com.sameerasw.airsync.widget.AirSyncWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/** Every transport uses one authenticated session, business router and reconnect policy. */
object WebSocketUtil {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = ConnectionLifecycle()
    private val _status = MutableStateFlow(lifecycle.snapshot)
    val status = _status.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connectionState = _connected.asStateFlow()
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val manualListeners = CopyOnWriteArraySet<() -> Unit>()
    private var appContext: Context? = null
    @Volatile var currentIpAddress: String? = null; private set
    private var currentPort: Int = 6996
    @Volatile private var socket: WebSocket? = null
    @Volatile private var relay: RelayConnection? = null
    @Volatile private var key: SecretKey? = null
    @Volatile private var clipboardSession: Any? = null
    private val manualGate = ManualConnectionGate()
    private var pendingSockets = mutableListOf<WebSocket>()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var attemptJob: Job? = null
    private var timeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private var statusCallback: ((Boolean) -> Unit)? = null
    private var messageCallback: ((String) -> Unit)? = null
    private var authTimeoutCallback: (() -> Unit)? = null
    private var publishedGeneration = lifecycle.generation

    /** Only fixed codes and lifecycle metadata; never log error text or peer material. */
    private fun diagnostic(event: String, token: Long = lifecycle.generation) {
        val snapshot = lifecycle.snapshot
        val mode = snapshot.mode.takeIf { it in setOf("local", "relay", "usb") } ?: "other"
        Log.i("AirSyncConnection", "event=$event mode=$mode phase=${snapshot.phase} generation=${lifecycle.generation} token=$token")
    }

    private fun publish() {
        if (_status.value != lifecycle.snapshot || publishedGeneration != lifecycle.generation) {
            diagnostic("transition")
            publishedGeneration = lifecycle.generation
        }
        _status.value = lifecycle.snapshot
        _connected.value = lifecycle.snapshot.connected
        listeners.forEach { runCatching { it(_connected.value) } }
        if (lifecycle.snapshot.phase in setOf(ConnectionPhase.CONNECTED, ConnectionPhase.DISCONNECTED, ConnectionPhase.ERROR)) {
            statusCallback?.invoke(_connected.value)
        }
        appContext?.let { context ->
            runCatching { AirSyncWidgetProvider.updateAllWidgets(context) }
        }
    }
    private fun phase(value: ConnectionPhase, error: String? = null) { lifecycle.transition(value, error); publish() }
    fun isConnected() = lifecycle.snapshot.connected
    fun isWifiConnected() = isConnected() && lifecycle.snapshot.mode == "local"
    fun isConnecting() = lifecycle.snapshot.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.WAITING_FOR_PEER, ConnectionPhase.HANDSHAKING)
    fun isRelayMode() = lifecycle.snapshot.mode == "relay"
    fun isUsbMode() = lifecycle.snapshot.mode == "usb"
    fun registerConnectionStatusListener(listener: (Boolean) -> Unit) { listeners.add(listener) }
    fun unregisterConnectionStatusListener(listener: (Boolean) -> Unit) { listeners.remove(listener) }
    fun registerManualConnectListener(listener: () -> Unit) { manualListeners.add(listener) }
    fun unregisterManualConnectListener(listener: () -> Unit) { manualListeners.remove(listener) }

    fun connect(context: Context, ipAddress: String, port: Int, symmetricKey: String?, onConnectionStatus: ((Boolean) -> Unit)? = null,
                onMessage: ((String) -> Unit)? = null, manualAttempt: Boolean = true, onHandshakeTimeout: (() -> Unit)? = null) {
        connectTransport(context, ipAddress, port, symmetricKey, onConnectionStatus, onMessage, manualAttempt, onHandshakeTimeout)
    }
    /** ADB reverse owns this fixed endpoint. Never accept addresses, keys or ports from a broadcast. */
    suspend fun connectUsb(context: Context): Boolean = withContext(Dispatchers.Main.immediate) {
        val token = lifecycle.generation
        diagnostic("usb_request", token)
        val ds = DataStoreManager.getInstance(context)
        val preferred = ds.getTransportMode().first()
        val pairedKey = if (preferred == "relay") ds.getRelaySymmetricKey().first() else ds.getLastConnectedDevice().first()?.symmetricKey
        val enabled = ds.getAutoReconnectEnabled().first()
        val paused = enabled && ds.isAppPaused().first()
        val manual = ds.getUserManuallyDisconnected().first()
        val decision = UsbAdmissionPolicy.decide(manualGate.stopped, manual, enabled, paused,
            TransportCapabilities.validKey(pairedKey), isUsbMode() && (isConnected() || isConnecting()), lifecycle.accepts(token))
        diagnostic("usb_admission_${decision.code}", token)
        when (decision) {
            UsbAdmissionDecision.ALREADY_ACTIVE -> return@withContext true
            UsbAdmissionDecision.ACCEPTED -> Unit
            else -> return@withContext false
        }
        connectTransport(context, "127.0.0.1", 16996, pairedKey, manualAttempt = false, usbAttempt = true)
        AirSyncService.start(context, null)
        true
    }
    fun disconnectUsb(context: Context) { scope.launch {
        diagnostic("usb_disconnect_request")
        if (isUsbMode() && !manualGate.stopped && (isConnected() || isConnecting())) lost(context, lifecycle.generation, "USB 已断开", "usb_detached")
    } }
    private fun connectTransport(context: Context, ipAddress: String, port: Int, symmetricKey: String?, onConnectionStatus: ((Boolean) -> Unit)? = null,
                onMessage: ((String) -> Unit)? = null, manualAttempt: Boolean = true, onHandshakeTimeout: (() -> Unit)? = null,
                usbAttempt: Boolean = false) {
        scope.launch {
            if (!manualAttempt && (manualGate.stopped || (!usbAttempt && (isConnected() || isConnecting())))) {
                if (usbAttempt) diagnostic("usb_transport_manual_stop")
                return@launch
            }
            appContext = context.applicationContext
            attemptJob?.cancel()
            closeTransport()
            cancelAutoReconnect()
            val token = lifecycle.begin(if (usbAttempt) "usb" else lifecycle.snapshot.mode)
            statusCallback = onConnectionStatus
            messageCallback = onMessage
            authTimeoutCallback = onHandshakeTimeout
            if (manualAttempt) { manualGate.resume(); manualListeners.forEach { it() } }
            publish()
            attemptJob = scope.launch {
                val ds = DataStoreManager.getInstance(context)
                if (manualAttempt) ds.setUserManuallyDisconnected(false)
                else {
                    val rejection = when {
                        ds.getUserManuallyDisconnected().first() -> "manual_stop"
                        !ds.getAutoReconnectEnabled().first() -> "auto_disabled"
                        ds.isAppPaused().first() -> "paused"
                        else -> null
                    }
                    if (rejection != null) {
                        if (usbAttempt) diagnostic("usb_transport_$rejection", token)
                        if (lifecycle.accepts(token)) { lifecycle.disconnect(); publish() }
                        return@launch
                    }
                }
                val mode = if (usbAttempt) "usb" else ds.getTransportMode().first()
                if (!lifecycle.accepts(token)) {
                    if (usbAttempt) diagnostic("usb_transport_superseded", token)
                    return@launch
                }
                lifecycle.transition(ConnectionPhase.CONNECTING)
                // A generation begins only once; changing mode here still invalidates all prior sockets.
                val session = lifecycle.begin(mode)
                publish()
                val keyText = symmetricKey?.takeIf { it.isNotBlank() } ?: if (mode == "relay") ds.getRelaySymmetricKey().first() else ds.getLastConnectedDevice().first()?.symmetricKey
                if (!TransportCapabilities.validKey(keyText)) {
                    if (usbAttempt) diagnostic("usb_transport_invalid_pairing", session)
                    phase(ConnectionPhase.ERROR, "请填写有效的 AES 加密密钥（从 Mac 复制，双方必须一致）")
                    return@launch
                }
                key = CryptoUtil.decodeKey(keyText!!.trim())
                currentPort = port
                if (mode == "relay") {
                    val url = ds.getRelayServerUrl().first()
                    val room = ds.getRelayRoom().first()
                    val secret = ds.getRelaySecret().first()
                    val pinnedCertSha256 = ds.getRelayPinnedCertSha256().first()
                    if (!lifecycle.accepts(session)) return@launch
                    if (!url.startsWith("wss://") || room.isBlank() || secret.length < 16) {
                        phase(ConnectionPhase.ERROR, context.getString(com.sameerasw.airsync.R.string.relay_err_check_config))
                        return@launch
                    }
                    relay = RelayConnection(context.applicationContext, url, room, secret, pinnedCertSha256,
                        onState = { state, error -> scope.launch {
                            if (!lifecycle.accepts(session)) return@launch
                            when (state) {
                                RelayConnection.State.WAITING -> { timeoutJob?.cancel(); SyncManager.stopPeriodicSync(); lifecycle.peer(false); publish() }
                                RelayConnection.State.PEER_ONLINE -> startHandshake(context, session)
                                RelayConnection.State.DISCONNECTED -> lost(context, session, error, "relay_disconnected")
                                RelayConnection.State.ERROR -> { diagnostic("relay_error", session); closeTransport(); phase(ConnectionPhase.ERROR, error ?: context.getString(com.sameerasw.airsync.R.string.relay_err_rejected)) }
                            }
                        } },
                        onMessage = { text -> scope.launch { receive(context, session, text) } }
                    ).also { it.connect() }
                } else {
                    val ips = ipAddress.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    if (ips.isEmpty()) { phase(ConnectionPhase.ERROR, "请填写 Mac 的局域网 IP 地址"); return@launch }
                    var failures = 0
                    ips.forEach { ip ->
                        val address = if (ip.contains(':')) "[${ip.removeSurrounding("[", "]")}]" else ip
                        val request = runCatching { Request.Builder().url("ws://$address:$port/socket").build() }.getOrNull()
                        if (request == null) { failures++; return@forEach }
                        val ws = client.newWebSocket(request, object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) { scope.launch {
                                if (!lifecycle.accepts(session) || socket != null) { webSocket.close(1000, "Obsolete attempt"); return@launch }
                                socket = webSocket
                                currentIpAddress = ip
                                pendingSockets.filter { it !== webSocket }.forEach { it.cancel() }
                                pendingSockets.clear()
                                startHandshake(context, session)
                            } }
                            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { if (socket === webSocket) receive(context, session, text) } }
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { scope.launch { if (socket === webSocket) lost(context, session, "连接已关闭", "socket_closed") } }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch {
                                if (!lifecycle.accepts(session)) return@launch
                                failures++
                                if (socket === webSocket || (socket == null && failures >= ips.size)) lost(context, session, "连接失败，请检查网络和 Mac 服务", "socket_failed")
                            } }
                        })
                        pendingSockets.add(ws)
                    }
                }
                if (lifecycle.snapshot.phase == ConnectionPhase.CONNECTING) {
                    timeoutJob?.cancel()
                    timeoutJob = scope.launch { delay(20_000); if (lifecycle.accepts(session) && lifecycle.snapshot.phase == ConnectionPhase.CONNECTING) lost(context, session, "连接服务器超时", "connection_timeout") }
                }
            }
        }
    }
    private fun startHandshake(context: Context, token: Long) {
        if (!lifecycle.accepts(token) || !lifecycle.beginHandshake()) return
        SyncManager.stopPeriodicSync()
        publish()
        SyncManager.performInitialSync(context)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(15_000)
            if (lifecycle.accepts(token) && !isConnected()) {
                authTimeoutCallback?.invoke()
                lost(context, token, "握手超时，请确认 Mac 在线且双方加密密钥一致", "handshake_timeout")
            }
        }
    }
    private suspend fun receive(context: Context, token: Long, encrypted: String) {
        if (!lifecycle.accepts(token)) return
        val plain = key?.let { CryptoUtil.decryptMessage(encrypted, it) }
        if (plain == null) {
            lost(context, token, "无法解密 Mac 消息，请检查双方加密密钥是否一致", "decryption_failed"); return
        }
        val type = runCatching { JSONObject(plain).optString("type") }.getOrNull() ?: return
        if (!TransportCapabilities.allows(lifecycle.snapshot.mode, type)) return
        if (!isConnected()) {
            if (type != "macInfo" || lifecycle.snapshot.phase != ConnectionPhase.HANDSHAKING) return
            lifecycle.authenticated()
            clipboardSession = Any()
            ClipboardSyncManager.resetSession()
            timeoutJob?.cancel(); cancelAutoReconnect(); retryCount = 0
            // Persist the authenticated endpoint before publishing success. UI
            // callbacks and the macInfo metadata update must see the same device.
            val ds = DataStoreManager.getInstance(context)
            val payload = JSONObject(plain).optJSONObject("data")
            val last = ds.getLastConnectedDevice().first()
            if (!lifecycle.accepts(token)) return
            val name = payload?.optString("name")?.takeIf { it.isNotBlank() } ?: last?.name ?: "Mac"
            val device = last ?: com.sameerasw.airsync.domain.model.ConnectedDevice(
                name = name, ipAddress = "", port = "6996", lastConnected = System.currentTimeMillis(), isPlus = true)
            ds.saveLastConnectedDevice(if (isRelayMode() || isUsbMode()) device.copy(name = name, lastConnected = System.currentTimeMillis())
                else device.copy(name = name, ipAddress = currentIpAddress ?: "", port = currentPort.toString(),
                    symmetricKey = java.util.Base64.getEncoder().encodeToString(key!!.encoded), lastConnected = System.currentTimeMillis()))
            if (!lifecycle.accepts(token)) return
            publish()
            SyncManager.startPeriodicSync(context)
            SyncManager.checkAndSyncDeviceStatus(context, true)
            AirSyncService.start(context, null)
            MediaNotificationListener.syncActiveNotifications()
        }
        WebSocketMessageHandler.handleIncomingMessage(context, plain)
        messageCallback?.invoke(plain)
    }
    private fun lost(context: Context, token: Long, reason: String?, source: String) {
        diagnostic("lost_$source", token)
        if (!lifecycle.accepts(token)) {
            diagnostic("lost_ignored_superseded", token)
            return
        }
        closeTransport()
        lifecycle.disconnect() // invalidate late socket callbacks before scheduling the retry
        phase(ConnectionPhase.RECONNECTING, reason)
        scheduleReconnect(context)
    }
    private fun scheduleReconnect(context: Context) {
        if (reconnectJob?.isActive == true || manualGate.stopped) return
        val originalToken = lifecycle.generation
        reconnectJob = scope.launch {
            val ds = DataStoreManager.getInstance(context)
            val preferred = ds.getTransportMode().first()
            if (!lifecycle.accepts(originalToken)) return@launch
            // USB is ephemeral: failures restore the saved network mode before retrying.
            if (isUsbMode()) {
                lifecycle.begin(preferred)
                phase(ConnectionPhase.RECONNECTING)
                if (preferred == "local") AirSyncService.startScanning(context)
            }
            val token = lifecycle.generation
            if (!lifecycle.retryAllowed(token, ds.getTransportMode().first(), ds.getUserManuallyDisconnected().first(), ds.getAutoReconnectEnabled().first())) {
                if (lifecycle.accepts(token)) phase(ConnectionPhase.DISCONNECTED)
                return@launch
            }
            delay((1000L shl retryCount++.coerceAtMost(5)).coerceAtMost(30_000))
            if (!lifecycle.retryAllowed(token, ds.getTransportMode().first(), ds.getUserManuallyDisconnected().first(), ds.getAutoReconnectEnabled().first()) || manualGate.stopped) return@launch
            val last = ds.getLastConnectedDevice().first()
            reconnectJob = null
            connect(context, last?.ipAddress ?: "", last?.port?.toIntOrNull() ?: 6996, if (isRelayMode()) null else last?.symmetricKey, manualAttempt = false)
        }
    }
    fun sendMessage(message: String): Boolean {
        val type = runCatching { JSONObject(message).optString("type") }.getOrNull() ?: return false
        if (!TransportCapabilities.allows(lifecycle.snapshot.mode, type)) return false
        if (!isConnected() && !(lifecycle.snapshot.phase == ConnectionPhase.HANDSHAKING && type == "device")) return false
        val encrypted = key?.let { CryptoUtil.encryptMessage(message, it) } ?: return false
        val token = lifecycle.generation
        val sent = if (isRelayMode()) relay?.send(encrypted) == true else socket?.send(encrypted) == true
        if (!sent) appContext?.let { context -> scope.launch { lost(context, token, "消息发送失败，正在重新连接", "send_failed") } }
        return sent
    }
    internal fun localSessionToken(): Long? = lifecycle.generation.takeIf { !isRelayMode() && isConnected() }
    internal fun clipboardSessionToken(): Any? = clipboardSession.takeIf { isConnected() }
    /** Called on Main, with the original authenticated session, never a replacement peer. */
    internal fun sendClipboardMessage(session: Any, message: String): Boolean {
        if (session !== clipboardSession || !isConnected()) return false
        return sendMessage(message)
    }
    internal fun ownsLocalSession(token: Long) = lifecycle.accepts(token) && !isRelayMode() && isConnected()
    internal fun sendLocalFileMessage(token: Long, message: String): Boolean {
        // Hold the original socket, so replacing the connection can never redirect
        // an in-flight file task to a different peer.
        val target = socket ?: return false
        val sessionKey = key ?: return false
        if (!ownsLocalSession(token)) return false
        val encrypted = CryptoUtil.encryptMessage(message, sessionKey) ?: return false
        if (!ownsLocalSession(token) || socket !== target) return false
        return target.send(encrypted)
    }
    private fun closeTransport() {
        clipboardSession = null
        LocalFileTransfer.reset()
        timeoutJob?.cancel(); timeoutJob = null
        relay?.close(); relay = null
        val oldSocket = socket; socket = null; oldSocket?.cancel()
        pendingSockets.forEach { it.cancel() }; pendingSockets.clear()
        currentIpAddress = null
        SyncManager.stopPeriodicSync()
    }
    fun disconnect(context: Context? = null, manual: Boolean = false) {
        diagnostic(if (manual) "disconnect_manual" else "disconnect")
        if (manual) { sendMessage("{\"type\":\"userDisconnected\",\"data\":{}}"); manualGate.stop() }
        lifecycle.disconnect() // synchronous cancellation fence for every asynchronous callback
        scope.launch {
            attemptJob?.cancel(); cancelAutoReconnect(); closeTransport(); publish()
            val ctx = context ?: appContext
            ctx?.let {
                if (manual) DataStoreManager.getInstance(it).setUserManuallyDisconnected(true)
                MacDeviceStatusManager.cleanup(it)
                NotificationUtil.clearContinueBrowsingNotifications(it)
            }
        }
    }
    fun cleanup() { disconnect(); SyncManager.reset() }
    fun cancelAutoReconnect() { reconnectJob?.cancel(); reconnectJob = null }
    fun isAutoReconnecting() = reconnectJob?.isActive == true
    fun stopAutoReconnect(context: Context) = disconnect(context, manual = true)
    /** Call only after an explicit user resume/reconnect has cleared the persisted stop. */
    fun resumeAutoReconnect(context: Context) { manualGate.resume(); requestAutoReconnect(context) }
    fun requestAutoReconnect(context: Context) { scope.launch {
        appContext = context.applicationContext
        if (!isConnected() && !isConnecting() && !manualGate.stopped) scheduleReconnect(context)
    } }
}
