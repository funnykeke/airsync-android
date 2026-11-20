package com.sameerasw.airsync.utils

import android.content.Context
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The local WebSocket file protocol; relay never enters this handler. */
object LocalFileTransfer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private data class Incoming(val file: File, val handle: RandomAccessFile, val name: String, val directory: File,
        val layout: FileChunkLayout, val checksum: String, val received: java.util.BitSet = java.util.BitSet())
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val incomingLock = Any()
    private val acknowledgements = ConcurrentHashMap<String, Channel<Int>>()
    private val verifications = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun reset() {
        scope.coroutineContext.cancelChildren()
        acknowledgements.values.forEach { it.close() }
        acknowledgements.clear()
        verifications.values.forEach { it.cancel() }
        verifications.clear()
        val abandoned = synchronized(incomingLock) { incoming.values.toList().also { incoming.clear() } }
        scope.launch { abandoned.forEach { runCatching { it.handle.close() }; it.file.delete() } }
    }

    fun handle(context: Context, type: String, data: JSONObject) {
        val token = WebSocketUtil.localSessionToken() ?: return
        if (type == "fileChunkAck") {
            acknowledgements[data.optString("id")]?.trySend(data.optInt("index", -1))
            return
        }
        if (type == "transferVerified") { verifications[data.optString("id")]?.complete(data.optBoolean("verified")); return }
        scope.launch {
            if (!WebSocketUtil.ownsLocalSession(token)) return@launch
            val id = data.optString("id")
            try {
                when (type) {
                    "filePull" -> sendFile(context, data.getString("path"), token)
                    "fileTransferInit" -> {
                        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
                        require(!incoming.containsKey(id) && incoming.size < 3)
                        val name = data.getString("name")
                        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000'))
                        val layout = FileChunkLayout(data.getLong("size"), data.optInt("chunkSize", 65536))
                        val directory = FileTransferDestination.resolve(Environment.getExternalStorageDirectory(), data.optString("targetPath").ifBlank { null })
                        require(context.cacheDir.usableSpace > layout.size)
                        val file = File.createTempFile("airsync-incoming-", ".part", context.cacheDir)
                        val transfer = Incoming(file, RandomAccessFile(file, "rw"), name, directory, layout, data.optString("checksum"))
                        synchronized(incomingLock) {
                            if (!WebSocketUtil.ownsLocalSession(token)) { transfer.handle.close(); file.delete(); return@launch }
                            incoming[id] = transfer
                        }
                    }
                    "fileChunk" -> {
                        val transfer = incoming[id] ?: error("未知文件传输")
                        val index = data.getInt("index")
                        val bytes = Base64.decode(data.getString("chunk"), Base64.DEFAULT)
                        require(bytes.size == transfer.layout.length(index))
                        transfer.handle.seek(transfer.layout.offset(index))
                        transfer.handle.write(bytes)
                        transfer.received.set(index)
                        send(token, "fileChunkAck", JSONObject().put("id", id).put("index", index))
                    }
                    "fileTransferComplete" -> {
                        val transfer = incoming.remove(id) ?: error("未知文件传输")
                        try {
                            transfer.handle.close()
                            require(transfer.received.cardinality() == transfer.layout.count && transfer.file.length() == transfer.layout.size)
                            require(transfer.checksum.isEmpty() || sha256(transfer.file).equals(transfer.checksum, true))
                            currentCoroutineContext().ensureActive()
                            check(WebSocketUtil.ownsLocalSession(token)) { "连接已改变" }
                            val directory = transfer.directory
                            directory.mkdirs()
                            require(directory.usableSpace > transfer.layout.size)
                            var target = File(directory, transfer.name)
                            var counter = 1
                            while (!target.createNewFile()) {
                                target = File(directory, "${File(transfer.name).nameWithoutExtension} (${counter++})" +
                                    File(transfer.name).extension.let { if (it.isEmpty()) "" else ".$it" })
                            }
                            try { transfer.file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } } }
                            catch (e: Exception) { target.delete(); throw e }
                            send(token, "transferVerified", JSONObject().put("id", id).put("verified", true))
                            withContext(Dispatchers.Main) { Toast.makeText(context, "文件已保存：${target.path}", Toast.LENGTH_LONG).show() }
                        } finally { runCatching { transfer.handle.close() }; transfer.file.delete() }
                    }
                    "fileTransferCancel" -> {
                        incoming.remove(id)?.let { it.handle.close(); it.file.delete() }
                        acknowledgements.remove(id)?.close()
                        verifications.remove(id)?.cancel()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                incoming.remove(id)?.let { runCatching { it.handle.close() }; it.file.delete() }
                send(token, "fileTransferCancel", JSONObject().put("id", id).put("error", error.message ?: "文件传输失败"))
                withContext(Dispatchers.Main) { Toast.makeText(context, "文件传输失败：${error.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private suspend fun sendFile(context: Context, path: String, token: Long) {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val file = File(path).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile && file.canRead()) { "无法读取此文件" }
        val layout = FileChunkLayout(file.length(), 65536)
        val checksum = sha256(file)
        currentCoroutineContext().ensureActive()
        check(WebSocketUtil.ownsLocalSession(token)) { "连接已改变" }
        val id = UUID.randomUUID().toString()
        val ack = Channel<Int>(Channel.BUFFERED)
        val verification = CompletableDeferred<Boolean>()
        acknowledgements[id] = ack
        verifications[id] = verification
        try {
            check(send(token, "fileTransferInit", JSONObject().put("id", id).put("name", file.name).put("size", layout.size)
                .put("mime", "application/octet-stream").put("chunkSize", layout.chunkSize).put("checksum", checksum)))
            file.inputStream().use { input ->
                for (index in 0 until layout.count) {
                    currentCoroutineContext().ensureActive()
                    val bytes = ByteArray(layout.length(index))
                    var read = 0
                    while (read < bytes.size) { val n = input.read(bytes, read, bytes.size - read); check(n > 0); read += n }
                    val payload = JSONObject().put("id", id).put("index", index).put("chunk", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    var delivered = false
                    for (attempt in 0..2) {
                        check(send(token, "fileChunk", payload)) { "连接已断开" }
                        delivered = withTimeoutOrNull(2000) { while (ack.receive() != index) { }; true } == true
                        if (delivered) break
                    }
                    check(delivered) { "等待文件确认超时" }
                }
            }
            check(send(token, "fileTransferComplete", JSONObject().put("id", id).put("name", file.name).put("size", layout.size).put("checksum", checksum)))
            check(withTimeout(10000) { verification.await() }) { "Mac 文件校验失败" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            send(token, "fileTransferCancel", JSONObject().put("id", id).put("error", "文件发送中断"))
            throw error
        } finally { acknowledgements.remove(id); verifications.remove(id); ack.close() }
    }

    private fun send(token: Long, type: String, data: JSONObject) = WebSocketUtil.sendLocalFileMessage(token, JSONObject().put("type", type).put("data", data).toString())
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
