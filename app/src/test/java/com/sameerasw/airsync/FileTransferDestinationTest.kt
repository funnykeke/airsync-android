package com.sameerasw.airsync

import com.sameerasw.airsync.utils.FileTransferDestination
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FileTransferDestinationTest {
    @Test fun preservesChosenFolderAndDefaultsToDownloads() {
        val root = File(System.getProperty("java.io.tmpdir"), "airsync-storage").canonicalFile
        assertEquals(File(root, "Download"), FileTransferDestination.resolve(root, null))
        assertEquals(File(root, "中文"), FileTransferDestination.resolve(root, File(root, "中文").path))
    }
    @Test fun rejectsEscapingStorageRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "airsync-storage").canonicalFile
        for (path in listOf(File(root, "../secret").path, root.path + "-other", "/private/secret")) {
            try { FileTransferDestination.resolve(root, path); fail("Accepted destination outside shared storage") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
