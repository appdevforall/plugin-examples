package com.appdevforall.pair.plugin.data

import java.nio.ByteBuffer

object FileChunkCodec {

    class Chunk(val path: String, val offset: Long, val data: ByteArray)

    fun encode(path: String, offset: Long, source: ByteArray, length: Int): ByteArray {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(HEADER_BYTES + pathBytes.size + length)
        buffer.putInt(pathBytes.size)
        buffer.put(pathBytes)
        buffer.putLong(offset)
        buffer.putInt(length)
        buffer.put(source, 0, length)
        return buffer.array()
    }

    fun decode(buffer: ByteBuffer): Chunk {
        val pathLength = buffer.int
        val pathBytes = ByteArray(pathLength)
        buffer.get(pathBytes)
        val offset = buffer.long
        val dataLength = buffer.int
        val data = ByteArray(dataLength)
        buffer.get(data)
        return Chunk(String(pathBytes, Charsets.UTF_8), offset, data)
    }

    private const val HEADER_BYTES = 4 + 8 + 4
}
