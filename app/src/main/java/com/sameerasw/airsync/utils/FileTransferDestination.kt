package com.sameerasw.airsync.utils

import java.io.File

object FileTransferDestination {
    fun resolve(storageRoot: File, targetPath: String?): File {
        val root = storageRoot.canonicalFile
        val target = SharedStoragePath.resolve(root, targetPath?.takeIf { it.isNotBlank() } ?: "Download")
        require(target.toPath().startsWith(root.toPath())) { "目标目录不在共享存储中" }
        return target
    }
}
