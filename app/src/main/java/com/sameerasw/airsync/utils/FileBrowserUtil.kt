package com.sameerasw.airsync.utils

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject

object FileBrowserUtil {
    /** Bounds are canonical; display paths retain the Mac browser's /sdcard root. */
    fun listDirectory(path: String?, showHidden: Boolean = false): String {
        val root = Environment.getExternalStorageDirectory()
        var display = "/sdcard"
        val data = JSONObject()
        try {
            val directory = SharedStoragePath.resolve(root, path)
            display = SharedStoragePath.display(root, directory)
            require(directory.isDirectory) { "Directory unavailable" }
            val items = directory.listFiles() ?: error("Access denied")
            val result = JSONArray()
            for (file in items.sortedBy { it.name.lowercase() }.sortedByDescending { it.isDirectory }) {
                if (!showHidden && file.name.startsWith('.')) continue
                // Do not expose metadata of symlinks pointing outside shared storage.
                if (runCatching { SharedStoragePath.resolve(root, file.path) }.isFailure) continue
                result.put(JSONObject().put("name", file.name).put("isDir", file.isDirectory)
                    .put("size", if (file.isDirectory) 0 else file.length()).put("time", file.lastModified()))
            }
            data.put("items", result)
        } catch (_: Exception) { data.put("error", "Directory unavailable or access denied").put("items", JSONArray()) }
        data.put("path", display)
        return JSONObject().put("type", "browseData").put("data", data).toString()
    }
}
