package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the encoder-only guard against the files a full header parse gives up on. Classifying an
 * embedding model as [GgufModelInspector.ModelKind.UNKNOWN] lets it reach a causal `llama_decode`,
 * which aborts the whole IDE process — the crash ADFA-4388 added the guard to prevent.
 */
class GgufModelInspectorTest {

    @Test
    fun givenAWellFormedEmbeddingModel_whenClassifying_thenReportsEmbedding() {
        val bytes = gguf(architectureEntry(EMBEDDING_ARCH))

        assertEquals(EMBEDDING_ARCH, GgufHeaderReader.read { bytes.inputStream() }?.architecture)
        assertEquals(GgufModelInspector.ModelKind.EMBEDDING, classify(bytes).kind)
    }

    @Test
    fun givenAnUnknownValueTypeAfterTheArchitecture_whenClassifying_thenStillReportsEmbedding() {
        val bytes = gguf(architectureEntry(EMBEDDING_ARCH), unknownTypeEntry("quirk"))

        assertNull(GgufHeaderReader.read { bytes.inputStream() })
        assertEquals(GgufModelInspector.ModelKind.EMBEDDING, classify(bytes).kind)
    }

    @Test
    fun givenMoreEntriesThanTheParserAccepts_whenClassifying_thenStillReportsEmbedding() {
        val bytes = gguf(architectureEntry(EMBEDDING_ARCH), declaredEntryCount = 5000L)

        assertNull(GgufHeaderReader.read { bytes.inputStream() })
        assertEquals(GgufModelInspector.ModelKind.EMBEDDING, classify(bytes).kind)
    }

    @Test
    fun givenAnUnparseableChatModel_whenClassifying_thenReportsChat() {
        val bytes = gguf(architectureEntry("llama"), unknownTypeEntry("quirk"))

        assertEquals(GgufModelInspector.ModelKind.CHAT, classify(bytes).kind)
    }

    @Test
    fun givenNoArchitectureAtAll_whenClassifying_thenFailsOpenAsUnknown() {
        val bytes = gguf(unknownTypeEntry("quirk"))

        assertEquals(GgufModelInspector.ModelKind.UNKNOWN, classify(bytes).kind)
    }

    @Test
    fun givenAnOpenerThatReturnsNoStream_whenClassifying_thenFailsOpenAsUnknown() {
        val result = GgufModelInspector.classify(null) { null }

        assertEquals(GgufModelInspector.ModelKind.UNKNOWN, result.kind)
    }

    /** Classifies the way the load path does: one full parse, then the architecture-only retry. */
    private fun classify(bytes: ByteArray): GgufModelInspector.Result {
        val openStream: () -> InputStream? = { bytes.inputStream() }
        return GgufModelInspector.classify(GgufHeaderReader.read(openStream), openStream)
    }
}

private const val EMBEDDING_ARCH = "nomic-bert"
private const val T_STRING = 8
private const val UNKNOWN_VALUE_TYPE = 99
private const val GGUF_VERSION = 3

/**
 * @param entries the metadata key/value pairs, already encoded, in order
 * @param declaredEntryCount the count to write into the header, defaulting to the truth; a larger
 *   one is how a corrupt file trips the parser's entry ceiling
 */
private fun gguf(vararg entries: ByteArray, declaredEntryCount: Long? = null): ByteArray =
    ByteArrayOutputStream().apply {
        write("GGUF".toByteArray(Charsets.US_ASCII))
        writeU32(GGUF_VERSION)
        writeU64(0L) // tensor count
        writeU64(declaredEntryCount ?: entries.size.toLong())
        entries.forEach { write(it) }
    }.toByteArray()

private fun architectureEntry(value: String): ByteArray =
    ByteArrayOutputStream().apply {
        writeString("general.architecture")
        writeU32(T_STRING)
        writeString(value)
    }.toByteArray()

/** A value type no parser can skip past, so it ends any parse that reaches it. */
private fun unknownTypeEntry(key: String): ByteArray =
    ByteArrayOutputStream().apply {
        writeString(key)
        writeU32(UNKNOWN_VALUE_TYPE)
    }.toByteArray()

private fun ByteArrayOutputStream.writeU32(value: Int) {
    for (shift in 0 until 32 step 8) write((value ushr shift) and 0xFF)
}

private fun ByteArrayOutputStream.writeU64(value: Long) {
    for (shift in 0 until 64 step 8) write(((value ushr shift) and 0xFF).toInt())
}

private fun ByteArrayOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeU64(bytes.size.toLong())
    write(bytes)
}
