package com.sameerasw.airsync

import com.sameerasw.airsync.utils.FileChunkLayout
import org.junit.Assert.*
import org.junit.Test

class FileChunkLayoutTest {
    @Test fun handlesFinalChunkAndEmptyFiles() {
        val layout = FileChunkLayout(10, 4)
        assertEquals(3, layout.count)
        assertEquals(4, layout.length(0))
        assertEquals(2, layout.length(2))
        assertEquals(8L, layout.offset(2))
        assertEquals(0, FileChunkLayout(0, 4096).length(0))
    }
    @Test fun rejectsInvalidAndOversizedChunks() {
        for (create in listOf<() -> Unit>({ FileChunkLayout(-1, 4) }, { FileChunkLayout(10, 0) }, { FileChunkLayout(10, 2_000_000) })) {
            try { create(); fail("Invalid transfer accepted") } catch (_: IllegalArgumentException) {}
        }
        val layout = FileChunkLayout(10, 4)
        for (index in listOf(-1, 3, Int.MAX_VALUE)) {
            try { layout.offset(index); fail("Invalid chunk index accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun doesNotOverflowForLargeFiles() {
        val layout = FileChunkLayout(5_000_000_000L, 65536)
        assertTrue(layout.count > 65536)
        assertTrue(layout.offset(layout.count - 1) > Int.MAX_VALUE)
    }
}
