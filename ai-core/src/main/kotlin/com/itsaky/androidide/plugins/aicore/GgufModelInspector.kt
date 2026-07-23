package com.itsaky.androidide.plugins.aicore

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Minimal GGUF header reader, just enough to tell whether a `.gguf` file is a chat/generation
 * model or an embedding (encoder-only) model.
 *
 * WHY: the native chat path runs a causal `llama_decode`. Handed an encoder-only model (BERT
 * family, e.g. all-MiniLM), llama.cpp hits a `GGML_ASSERT` and calls `abort()` — a SIGABRT that
 * no Kotlin `try/catch` can intercept, taking the whole IDE process down. We classify the file
 * up front so the backend can refuse chat gracefully instead of crashing. See ADFA-4388.
 *
 * This reads only the GGUF metadata header (`general.architecture` is almost always the first
 * key), skipping over values without loading the model, so it's cheap. It deliberately
 * **fails open**: any parse error or missing architecture is reported as [ModelKind.UNKNOWN] and
 * treated as chat-capable, so a genuine chat model is never wrongly blocked by a header quirk.
 */
object GgufModelInspector {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian

    // GGUF metadata value types.
    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    /**
     * Architectures that are encoder-only embedding models and cannot do causal generation.
     * The `contains("bert")` catch below covers the whole BERT family (bert, nomic-bert,
     * jina-bert-*, roberta, xlm-roberta, …); the explicit set covers the rest.
     */
    private val EMBEDDING_ARCHS = setOf("mpnet", "gte", "t5encoder")

    enum class ModelKind { CHAT, EMBEDDING, UNKNOWN }

    data class Result(val kind: ModelKind, val architecture: String?) {
        val isEmbeddingOnly: Boolean get() = kind == ModelKind.EMBEDDING
    }

    /** Reads [modelPath]'s GGUF header and classifies it. Never throws. */
    fun classify(modelPath: String): Result {
        val arch = try {
            readArchitecture(File(modelPath))
        } catch (_: Exception) {
            null
        } ?: return Result(ModelKind.UNKNOWN, null)

        val a = arch.lowercase()
        val isEmbedding = a.contains("bert") || a in EMBEDDING_ARCHS
        return Result(if (isEmbedding) ModelKind.EMBEDDING else ModelKind.CHAT, arch)
    }

    private fun readArchitecture(file: File): String? {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
            val magic = readU32(input)
            if (magic != GGUF_MAGIC) return null

            val version = readU32(input)
            // v1 used 32-bit counts/lengths; v2+ use 64-bit.
            val wide = version >= 2

            // tensor_count, then metadata_kv_count.
            readCount(input, wide)
            val kvCount = readCount(input, wide)

            for (i in 0 until kvCount) {
                val key = readString(input, wide)
                val valueType = readU32(input)
                if (key == "general.architecture" && valueType == T_STRING) {
                    return readRawString(input, wide)
                }
                skipValue(input, valueType, wide)
            }
        }
        return null
    }

    private fun skipValue(input: DataInputStream, type: Int, wide: Boolean) {
        when (type) {
            T_UINT8, T_INT8, T_BOOL -> skipFully(input, 1)
            T_UINT16, T_INT16 -> skipFully(input, 2)
            T_UINT32, T_INT32, T_FLOAT32 -> skipFully(input, 4)
            T_UINT64, T_INT64, T_FLOAT64 -> skipFully(input, 8)
            T_STRING -> skipFully(input, readCount(input, wide))
            T_ARRAY -> {
                val elemType = readU32(input)
                val n = readCount(input, wide)
                // GGUF arrays are never nested, so elements are scalars or strings.
                repeat(n.toInt().coerceAtLeast(0)) { skipValue(input, elemType, wide) }
            }
            else -> throw IllegalStateException("Unknown GGUF value type: $type")
        }
    }

    // --- little-endian primitives ---

    private fun readU32(input: DataInputStream): Int {
        val b0 = input.read(); val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
        if (b3 < 0) throw java.io.EOFException()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }

    private fun readU64(input: DataInputStream): Long {
        var v = 0L
        for (i in 0 until 8) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            v = v or ((b.toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    /** A length/count field: 64-bit on GGUF v2+, 32-bit on v1. */
    private fun readCount(input: DataInputStream, wide: Boolean): Long =
        if (wide) readU64(input) else readU32(input).toLong() and 0xFFFFFFFFL

    private fun readString(input: DataInputStream, wide: Boolean): String = readRawString(input, wide)

    private fun readRawString(input: DataInputStream, wide: Boolean): String {
        val len = readCount(input, wide)
        // Keys/arch strings are tiny; guard against a corrupt huge length.
        if (len < 0 || len > 1 shl 20) throw IllegalStateException("Unreasonable GGUF string length: $len")
        val bytes = ByteArray(len.toInt())
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipFully(input: DataInputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // skip() can return 0 near buffer boundaries; fall back to a read.
                if (input.read() < 0) throw java.io.EOFException()
                remaining -= 1
            }
        }
    }
}
