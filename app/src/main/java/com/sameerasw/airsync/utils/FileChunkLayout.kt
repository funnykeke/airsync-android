package com.sameerasw.airsync.utils

class FileChunkLayout(val size: Long, val chunkSize: Int) {
    val count: Int
    init {
        require(size >= 0 && chunkSize in 1..1_048_576)
        val chunks = if (size == 0L) 1 else (size - 1) / chunkSize + 1
        require(chunks <= Int.MAX_VALUE)
        count = chunks.toInt()
    }
    fun offset(index: Int): Long { require(index in 0 until count); return index.toLong() * chunkSize }
    fun length(index: Int): Int = minOf(chunkSize.toLong(), size - offset(index)).toInt()
}
