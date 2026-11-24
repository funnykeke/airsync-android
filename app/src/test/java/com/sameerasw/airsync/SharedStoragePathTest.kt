package com.sameerasw.airsync

import com.sameerasw.airsync.utils.SharedStoragePath
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class SharedStoragePathTest {
    @Test fun aliasesAndRelativeNamesKeepSdcardDisplayRoot() {
        val root = Files.createTempDirectory("shared").toFile()
        try {
            for (path in listOf("Download", "/sdcard/Download", File(root, "Download").path)) {
                val resolved = SharedStoragePath.resolve(root, path)
                assertEquals(File(root, "Download").canonicalFile, resolved)
                assertEquals("/sdcard/Download", SharedStoragePath.display(root, resolved))
            }
            assertEquals("/sdcard", SharedStoragePath.display(root, root))
        } finally { root.deleteRecursively() }
    }
    @Test fun traversalAbsoluteAndSymlinkEscapesAreRejected() {
        val root = Files.createTempDirectory("shared").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            for (path in listOf("../outside", outside.path, "/sdcard/../outside", "escape/file")) {
                try { SharedStoragePath.resolve(root, path); fail(path) } catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
