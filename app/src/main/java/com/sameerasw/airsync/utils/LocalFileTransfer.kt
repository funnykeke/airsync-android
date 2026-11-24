package com.sameerasw.airsync.utils

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Base64
import android.widget.Toast
import com.sameerasw.airsync.R
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Android/JSON adapter for the encrypted file protocol on every authenticated transport. */
object LocalFileTransfer {
    private var session: WebSocketUtil.FileSession? = null
    private var protocol: FileTransferProtocol? = null
    private val browseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val browseRequests = FileBrowseRequests()

    @Synchronized fun reset() {
        session = null
        protocol?.close(); protocol = null
        browseRequests.reset()
        browseScope.coroutineContext.cancelChildren()
    }
    @Synchronized private fun engine(context: Context, captured: WebSocketUtil.FileSession): FileTransferProtocol? {
        if (!WebSocketUtil.ownsFileSession(captured)) return null
        if (session !== captured) {
            protocol?.close()
            session = captured
            val app = context.applicationContext
            protocol = FileTransferProtocol(Environment.getExternalStorageDirectory(), app.cacheDir,
                { WebSocketUtil.ownsFileSession(captured) }, { send(captured, it) },
                onSaved = { browseScope.launch(Dispatchers.Main) {
                    if (WebSocketUtil.ownsFileSession(captured)) Toast.makeText(app, "文件已保存", Toast.LENGTH_LONG).show()
                } },
                onClipboardImage = { publishClipboardImage(app, captured, it) })
        }
        return protocol
    }

    internal fun handle(context: Context, type: String, data: JSONObject, captured: WebSocketUtil.FileSession) {
        val engine = engine(context, captured) ?: return
        val id = data.optString("id")
        try {
            if (type == "filePull") {
                // Reserve the caller's id before path resolution/hash so queued work is cancellable.
                engine.pull(data.getString("path"), if (data.has("id")) id else null)
            } else {
                engine.handle(FileTransferFrame(type, id, data.optString("name"), data.optLong("size"),
                    data.optInt("chunkSize", 65536), data.optString("checksum"), data.optString("targetPath").ifBlank { null },
                    data.optInt("index", -1), if (type == "fileChunk") Base64.decode(data.getString("chunk"), Base64.DEFAULT) else byteArrayOf(),
                    data.optBoolean("verified"), mime = data.optString("mime"),
                    clipboardImage = data.optBoolean("clipboardImage")))
            }
        } catch (_: Exception) {
            if (FileTransferProtocol.validId(id)) send(captured, FileTransferFrame("fileTransferCancel", id))
        }
    }

    private fun publishClipboardImage(context: Context, captured: WebSocketUtil.FileSession, file: File) {
        val ownsSession = { WebSocketUtil.ownsFileSession(captured) }
        if (!ownsSession()) return
        val written = ClipboardUtil.copyImageToClipboard(context, file, ownsSession)
        // Retain the delivered file for apps whose message field does not accept image clips.
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png"), null) }
        browseScope.launch(Dispatchers.Main) {
            if (!ownsSession()) return@launch
            val message = if (written) R.string.clipboard_image_received else R.string.clipboard_image_saved
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    internal fun browse(data: JSONObject, captured: WebSocketUtil.FileSession) {
        val path = data.optString("path")
        val hidden = data.optBoolean("showHidden")
        browseRequests.request({ WebSocketUtil.ownsFileSession(captured) },
            { FileBrowserUtil.listDirectory(path, hidden) }, { WebSocketUtil.sendFileMessage(captured, it) })
    }
    private fun send(captured: WebSocketUtil.FileSession, frame: FileTransferFrame): Boolean {
        val data = JSONObject().put("id", frame.id)
        when (frame.type) {
            "fileTransferInit", "fileTransferComplete" -> data.put("name", frame.name).put("size", frame.size).put("checksum", frame.checksum)
            "fileChunk" -> data.put("index", frame.index).put("chunk", Base64.encodeToString(frame.bytes, Base64.NO_WRAP))
            "fileChunkAck" -> data.put("index", frame.index)
            "transferVerified" -> data.put("verified", frame.verified)
            "fileTransferCancel" -> data.put("error", "文件传输中断")
        }
        if (frame.type == "fileTransferInit") data.put("chunkSize", frame.chunkSize).put("mime", "application/octet-stream")
        return WebSocketUtil.sendFileMessage(captured, JSONObject().put("type", frame.type).put("data", data).toString())
    }
}
