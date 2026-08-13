package com.itsaky.androidide.plugins.aiassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Tests for the GGUF metadata read that the memory estimate is based on.
 *
 * Every case runs against real bytes from [GgufWriter], since the point of the reader is that it
 * agrees with the on-disk format — and that it gives up cleanly when it doesn't.
 */
class GgufHeaderReaderTest {

    private fun read(bytes: ByteArray): GgufHeader? =
        GgufHeaderReader.read { ByteArrayInputStream(bytes) }

    /** The shape of gemma-3-1b, as a representative grouped-query-attention model. */
    private fun gemmaHeader(version: Int = 3) = GgufWriter(version)
        .string("general.architecture", "gemma3")
        .string("general.name", "gemma-3-1b-it")
        .uint32("gemma3.block_count", 26)
        .uint32("gemma3.embedding_length", 1152)
        .uint32("gemma3.attention.head_count", 4)
        .uint32("gemma3.attention.head_count_kv", 1)
        .build()

    @Test
    fun givenAGgufHeader_whenRead_thenEveryShapeValueIsReturned() {
        val header = read(gemmaHeader())

        assertEquals("gemma3", header?.architecture)
        assertEquals(26L, header?.blockCount)
        assertEquals(1152L, header?.embeddingLength)
        assertEquals(4L, header?.headCount)
        assertEquals(1L, header?.headCountKv)
    }

    @Test
    fun givenAVersionOneHeader_whenRead_thenItsNarrowerLengthsAreUnderstood() {
        // v1 wrote 32-bit counts and string lengths; misreading them desynchronizes every field.
        val header = read(gemmaHeader(version = 1))

        assertEquals("gemma3", header?.architecture)
        assertEquals(26L, header?.blockCount)
        assertEquals(1L, header?.headCountKv)
    }

    @Test
    fun givenAModelWithoutGroupedQueryAttention_whenRead_thenTheKvHeadCountIsAbsent() {
        val bytes = GgufWriter()
            .string("general.architecture", "llama")
            .uint32("llama.block_count", 32)
            .uint32("llama.embedding_length", 4096)
            .uint32("llama.attention.head_count", 32)
            .build()

        val header = read(bytes)

        assertEquals(32L, header?.headCount)
        assertNull(header?.headCountKv)
    }

    @Test
    fun givenShapeKeysBehindATokenizerArray_whenRead_thenTheArrayIsSkippedAndTheKeysAreFound() {
        val bytes = GgufWriter()
            .stringArray("tokenizer.ggml.tokens", List(500) { "token$it" })
            .string("general.architecture", "qwen2")
            .uint32("qwen2.block_count", 28)
            .uint32("qwen2.embedding_length", 1536)
            .uint32("qwen2.attention.head_count", 12)
            .uint32("qwen2.attention.head_count_kv", 2)
            .build()

        val header = read(bytes)

        assertEquals(28L, header?.blockCount)
        assertEquals(2L, header?.headCountKv)
    }

    @Test
    fun givenSixtyFourBitShapeValues_whenRead_thenTheyAreReadAtTheRightWidth() {
        val bytes = GgufWriter()
            .string("general.architecture", "llama")
            .uint64("llama.block_count", 32)
            .uint64("llama.embedding_length", 4096)
            .uint64("llama.attention.head_count", 32)
            .build()

        val header = read(bytes)

        assertEquals(32L, header?.blockCount)
        assertEquals(4096L, header?.embeddingLength)
    }

    @Test
    fun givenAShapeValueOfAnUnexpectedType_whenRead_thenOnlyThatValueIsLostAndTheRestSurvive() {
        // The value still has to be consumed, or every later key would be read from its middle.
        val bytes = GgufWriter()
            .string("general.architecture", "llama")
            .float32("llama.block_count", 32f)
            .uint32("llama.embedding_length", 4096)
            .uint32("llama.attention.head_count", 32)
            .build()

        val header = read(bytes)

        assertNull(header?.blockCount)
        assertEquals(4096L, header?.embeddingLength)
        assertEquals(32L, header?.headCount)
    }

    @Test
    fun givenAShapeValueOfAnAbsurdMagnitude_whenRead_thenItIsReturnedUnchangedForTheEstimatorToReject() {
        // The reader reports what the file declares; the plausibility ceiling lives in the estimator.
        val bytes = GgufWriter()
            .string("general.architecture", "llama")
            .uint64("llama.block_count", 1L shl 61)
            .uint32("llama.attention.head_count", 32)
            .build()

        val header = read(bytes)

        assertEquals(1L shl 61, header?.blockCount)
        assertEquals(32L, header?.headCount)
    }

    @Test
    fun givenAFileThatIsNotGguf_whenRead_thenThereIsNoHeader() {
        assertNull(read("This is a text file, not a model".toByteArray()))
    }

    @Test
    fun givenATruncatedHeader_whenRead_thenThereIsNoHeader() {
        val truncated = gemmaHeader().copyOfRange(0, 40)

        assertNull(read(truncated))
    }

    @Test
    fun givenAnUnopenableFile_whenRead_thenThereIsNoHeader() {
        assertNull(GgufHeaderReader.read { null })
    }

    @Test
    fun givenAStreamThatFailsMidRead_whenRead_thenThereIsNoHeader() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("device detached")
        }

        assertNull(GgufHeaderReader.read { failing })
    }

    @Test
    fun givenAHeaderClaimingAnAbsurdEntryCount_whenRead_thenThereIsNoHeader() {
        // Truncating is worse than giving up: a head_count_kv never reached reads as plain MHA.
        val bytes = GgufWriter()
            .string("general.architecture", "llama")
            .build()
            .let { corruptEntryCount(it) }

        assertNull(read(bytes))
    }

    @Test(timeout = 30_000)
    fun givenAnArrayCountThatWouldRunOnPastTheMetadata_whenRead_thenTheParseIsAbandoned() {
        // An array count is unbounded, so without a cap this walks the whole multi-GB file.
        val bytes = GgufWriter()
            .lyingStringArray("tokenizer.ggml.tokens", declaredCount = Long.MAX_VALUE / 2, values = listOf("a"))
            .string("general.architecture", "llama")
            .build()

        assertNull(GgufHeaderReader.read { EndlessStream(bytes) })
    }

    @Test(timeout = 30_000)
    fun givenOneStringValueClaimingAnEnormousLength_whenRead_thenTheByteBudgetStillStopsIt() {
        // One huge skip, not many small ones, so charging on completion never gets the chance.
        val bytes = GgufWriter()
            .lyingString("general.description", declaredLength = Long.MAX_VALUE / 2)
            .string("general.architecture", "llama")
            .build()

        assertNull(GgufHeaderReader.read { EndlessStream(bytes) })
    }

    @Test(timeout = 30_000)
    fun givenAnArrayNestedInsideAnArray_whenRead_thenItIsRejectedRatherThanFollowed() {
        // Recursing per nesting level ends in a StackOverflowError, not an Exception.
        val bytes = GgufWriter()
            .string("general.architecture", "llama")
            .nestedArray("tokenizer.ggml.merges")
            .build()

        assertNull(read(bytes))
    }

    @Test
    fun givenShapeKeysForAnotherArchitecture_whenRead_thenOnlyThisModelsAreReturned() {
        // A multimodal file carries the vision tower's shape alongside the language model's.
        val bytes = GgufWriter()
            .string("general.architecture", "qwen2")
            .uint32("clip.vision.block_count", 27)
            .uint32("clip.vision.embedding_length", 1152)
            .uint32("clip.vision.attention.head_count", 16)
            .uint32("qwen2.block_count", 28)
            .uint32("qwen2.embedding_length", 1536)
            .uint32("qwen2.attention.head_count", 12)
            .build()

        val header = read(bytes)

        assertEquals(28L, header?.blockCount)
        assertEquals(1536L, header?.embeddingLength)
        assertEquals(12L, header?.headCount)
    }

    @Test
    fun givenTheArchitectureDeclaredAfterTheShapeKeys_whenRead_thenTheyAreStillAttributed() {
        // general.architecture is conventionally first, but the format does not require it.
        val bytes = GgufWriter()
            .uint32("llama.block_count", 32)
            .uint32("llama.attention.head_count", 32)
            .string("general.architecture", "llama")
            .build()

        val header = read(bytes)

        assertEquals(32L, header?.blockCount)
        assertEquals(32L, header?.headCount)
    }

    @Test
    fun givenDeclaredKeyAndValueWidths_whenRead_thenTheyAreReturned() {
        val bytes = GgufWriter()
            .string("general.architecture", "gemma3")
            .uint32("gemma3.attention.key_length", 256)
            .uint32("gemma3.attention.value_length", 256)
            .build()

        val header = read(bytes)

        assertEquals(256L, header?.keyLength)
        assertEquals(256L, header?.valueLength)
    }

    /** Overwrites the entry count (bytes 16..23 of a v3 header) with a huge value. */
    private fun corruptEntryCount(bytes: ByteArray): ByteArray = bytes.copyOf().also {
        for (i in 16 until 24) it[i] = 0xFF.toByte()
        it[23] = 0x00 // keep it positive
    }

    /**
     * [prefix], then zeros forever — a stand-in for a multi-gigabyte model whose metadata lies, so
     * the reader has to stop itself rather than be stopped by end-of-file. The offset is a Long
     * deliberately: as an Int it wrapped past 2 GB and threw, ending the parse for the wrong reason.
     */
    private class EndlessStream(private val prefix: ByteArray) : InputStream() {

        private var offset = 0L

        override fun read(): Int =
            (if (offset < prefix.size) prefix[offset.toInt()].toInt() and 0xFF else 0)
                .also { offset++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var written = 0
            while (written < len && offset < prefix.size) {
                b[off + written] = prefix[offset.toInt()]
                offset++
                written++
            }
            if (written < len) {
                b.fill(0, off + written, off + len)
                offset += len - written
            }
            return len
        }
    }
}
