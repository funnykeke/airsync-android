package com.sameerasw.airsync.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class RootClipboardPhase { STOPPED, STARTING, LISTENING, RETRYING, ERROR }

/** Owns one event-driven child and its private pipes; never reads clipboard on a timer. */
object RootClipboardMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _phase = MutableStateFlow(RootClipboardPhase.STOPPED)
    val phase = _phase.asStateFlow()
    private var job: Job? = null
    private val lifecycle = Mutex()
    @Volatile private var process: Process? = null
    @Volatile private var generation = 0L
    private var failureLatched = false
    private const val MAX_LINE = 87_400

    @Synchronized
    fun start(context: Context, onInvalidated: () -> Unit, onText: (String) -> Unit) {
        if (failureLatched) { _phase.value = RootClipboardPhase.ERROR; return }
        if (job?.isActive == true) return
        val token = ++generation
        val apk = context.applicationInfo.sourceDir.replace("'", "'\\''")
        val command = "CLASSPATH='$apk' /system/bin/app_process /system/bin com.sameerasw.airsync.clipboard.RootClipboardReader"
        _phase.value = RootClipboardPhase.STARTING
        job = scope.launch { lifecycle.withLock {
            var failures = 0
            while (isActive && token == generation) {
                val ready = AtomicBoolean(false)
                var fatal = false
                var startedAt = 0L
                var child: Process? = null
                try {
                    val running = ProcessBuilder("su", "2000", "-c", command).start()
                    child = running
                    if (token != generation || !isActive) { terminate(running); break }
                    process = running
                    val stderr = launch { running.errorStream.use { input ->
                        val buffer = ByteArray(1024)
                        while (input.read(buffer) >= 0) { /* drain without logging contents */ }
                    } }
                    val authorizationTimeout = launch {
                        delay(90_000L)
                        if (!ready.get() && running.isAlive) terminate(running)
                    }
                    try {
                        running.inputStream.buffered().use { input ->
                            while (isActive && token == generation) {
                                val line = readLine(input) ?: break
                                if (token != generation) break
                                when {
                                    line == "READY" -> {
                                        ready.set(true)
                                        startedAt = android.os.SystemClock.elapsedRealtime()
                                        authorizationTimeout.cancel()
                                        updatePhase(token, RootClipboardPhase.LISTENING)
                                        Log.i("RootClipboardMonitor", "Background clipboard listener ready")
                                    }
                                    line.startsWith("TEXT:") && ready.get() -> {
                                        val bytes = Base64.decode(line.substring(5), Base64.NO_WRAP)
                                        if (bytes.size <= 65_536) onText(bytes.toString(Charsets.UTF_8))
                                    }
                                    line == "INVALIDATE" && ready.get() -> onInvalidated()
                                    line.startsWith("ERROR:") -> { fatal = true; break }
                                }
                            }
                        }
                    } finally {
                        authorizationTimeout.cancel()
                        terminate(running)
                        stderr.cancelAndJoin()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Log.w("RootClipboardMonitor", "Background clipboard listener exited")
                } finally {
                    child?.let { terminate(it) }
                    if (process === child) process = null
                }
                if (!isActive || token != generation) break
                // Never repeatedly request refused authorization or retry an incompatible API.
                if (!ready.get() || fatal) { fail(token); break }
                if (android.os.SystemClock.elapsedRealtime() - startedAt > 60_000L) failures = 0
                if (++failures > 3) { fail(token); break }
                updatePhase(token, RootClipboardPhase.RETRYING)
                delay(1_000L shl failures)
            }
        } }
    }

    @Synchronized
    private fun updatePhase(token: Long, phase: RootClipboardPhase) {
        if (token == generation) _phase.value = phase
    }

    @Synchronized
    private fun fail(token: Long) {
        if (token == generation) {
            failureLatched = true
            _phase.value = RootClipboardPhase.ERROR
        }
    }

    /** Only user interaction clears refused/incompatible authorization; reconnection does not. */
    @Synchronized
    fun clearFailure() { failureLatched = false }

    @Synchronized
    fun stop() {
        ++generation
        job?.cancel()
        job = null
        val child = process
        process = null
        // Closing stdin releases the child even if the su wrapper is still running.
        runCatching { child?.outputStream?.close() }
        if (child != null) scope.launch { terminate(child) }
        _phase.value = RootClipboardPhase.STOPPED
    }

    private fun terminate(child: Process) {
        runCatching { child.outputStream.close() }
        runCatching {
            if (!child.waitFor(1, TimeUnit.SECONDS)) {
                child.destroy()
                if (!child.waitFor(1, TimeUnit.SECONDS)) child.destroyForcibly()
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
            check(bytes.size() < MAX_LINE) { "Oversize clipboard frame" }
            bytes.write(next)
        }
    }
}
