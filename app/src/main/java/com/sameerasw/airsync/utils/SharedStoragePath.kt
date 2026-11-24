package com.sameerasw.airsync.utils

import java.io.File

object SharedStoragePath {
    fun resolve(storageRoot: File, path: String?): File {
        val root = storageRoot.canonicalFile
        val requested = path.orEmpty().trimEnd('/')
        val target = when {
            requested.isBlank() || requested == "/sdcard" -> root
            requested.startsWith("/sdcard/") -> File(root, requested.removePrefix("/sdcard/"))
            File(requested).isAbsolute -> File(requested)
            else -> File(root, requested)
        }.canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "Path outside shared storage" }
        return target
    }
    fun display(storageRoot: File, target: File): String {
        val resolved = resolve(storageRoot, target.path)
        val relative = storageRoot.canonicalFile.toPath().relativize(resolved.toPath()).toString()
        return "/sdcard" + if (relative.isEmpty()) "" else "/$relative"
    }
}
