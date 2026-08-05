package com.itsaky.androidide.plugins.aiassistant.util

import java.io.ByteArrayOutputStream

/**
 * Writes GGUF headers for tests, so the reader is exercised against real bytes rather than a stub.
 *
 * @param version the GGUF version to declare; 1 uses 32-bit lengths and counts, 2+ use 64-bit
 */
internal class GgufWriter(private val version: Int = 3) {

    private companion object {
        const val T_UINT32 = 4
        const val T_FLOAT32 = 6
        const val T_STRING = 8
        const val T_ARRAY = 9
        const val T_UINT64 = 10
    }

    private val wide = version >= 2
    private val entries = ByteArrayOutputStream()
    private var entryCount = 0L

    fun string(key: String, value: String): GgufWriter = entry(key, T_STRING) { writeString(value) }

    fun uint32(key: String, value: Long): GgufWriter = entry(key, T_UINT32) { writeU32(value) }

    fun uint64(key: String, value: Long): GgufWriter = entry(key, T_UINT64) { writeU64(value) }

    fun float32(key: String, value: Float): GgufWriter =
        entry(key, T_FLOAT32) { writeU32(java.lang.Float.floatToIntBits(value).toLong() and 0xFFFFFFFFL) }

    /** A tokenizer-sized value, so tests can check that the reader skips past one correctly. */
    fun stringArray(key: String, values: List<String>): GgufWriter = entry(key, T_ARRAY) {
        writeU32(T_STRING.toLong())
        writeCount(values.size.toLong())
        values.forEach { writeString(it) }
    }

    /**
     * An array whose declared length lies about how much data follows, for testing the reader's
     * bounds. A real file corrupted mid-download looks like this.
     *
     * @param declaredCount the element count written to the file, however untrue
     * @param values the elements actually written
     */
    fun lyingStringArray(key: String, declaredCount: Long, values: List<String>): GgufWriter =
        entry(key, T_ARRAY) {
            writeU32(T_STRING.toLong())
            writeCount(declaredCount)
            values.forEach { writeString(it) }
        }

    /**
     * A string value whose declared length lies about how much data follows. Distinct from
     * [lyingStringArray]: this is one enormous skip rather than many small ones, which is what a
     * byte budget charged only on completion fails to catch.
     */
    fun lyingString(key: String, declaredLength: Long): GgufWriter =
        entry(key, T_STRING) { writeCount(declaredLength) }

    /**
     * An array whose elements are themselves arrays. The format forbids this, and following one
     * recurses a level per nesting — the shape that used to end in a StackOverflowError.
     */
    fun nestedArray(key: String, declaredCount: Long = 1L): GgufWriter = entry(key, T_ARRAY) {
        writeU32(T_ARRAY.toLong())
        writeCount(declaredCount)
    }

    /** @return the complete header: magic, version, tensor count, entry count, then the entries. */
    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        out.writeU32(version.toLong())
        out.writeCount(0L) // tensor count
        out.writeCount(entryCount)
        out.write(entries.toByteArray())
        return out.toByteArray()
    }

    private fun entry(key: String, type: Int, writeValue: ByteArrayOutputStream.() -> Unit): GgufWriter {
        entries.writeString(key)
        entries.writeU32(type.toLong())
        entries.writeValue()
        entryCount++
        return this
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeCount(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeCount(value: Long) {
        if (wide) writeU64(value) else writeU32(value)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        for (shift in 0 until 4) write(((value shr (8 * shift)) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeU64(value: Long) {
        for (shift in 0 until 8) write(((value shr (8 * shift)) and 0xFF).toInt())
    }
}
