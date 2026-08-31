package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.DEFAULT_CONTEXT_TOKENS
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the fail-open contract of the read-then-choose pair the load path runs: no way of failing to
 * read a header may propagate out of it, because the caller is a model load that should proceed at
 * the default context instead of aborting.
 */
class ModelContextSizingTest {

    @Test
    fun givenAnOpenerThatThrows_whenSizing_thenReturnsTheDefaultContext() {
        assertDefaultContext { throw IOException("permission denied") }
    }

    @Test
    fun givenAnOpenerReturningNoStream_whenSizing_thenReturnsTheDefaultContext() {
        assertDefaultContext { null }
    }

    @Test
    fun givenAStreamThatIsNotGguf_whenSizing_thenReturnsTheDefaultContext() {
        assertDefaultContext { "not a model file".byteInputStream() }
    }

    @Test
    fun givenAStreamThatThrowsMidRead_whenSizing_thenReturnsTheDefaultContext() {
        assertDefaultContext { ThrowingStream() }
    }

    @Test
    fun givenUnknownFreeMemory_whenSizing_thenReturnsTheDefaultContext() {
        assertEquals(DEFAULT_CONTEXT_TOKENS, ContextSizePolicy.choose(header(), null, MODEL_SIZE))
    }

    @Test
    fun givenUnknownModelSize_whenSizing_thenReturnsTheDefaultContext() {
        assertEquals(DEFAULT_CONTEXT_TOKENS, ContextSizePolicy.choose(header(), Long.MAX_VALUE, null))
    }

    /**
     * Reads then sizes the way the load path does, with room to spare so only the header decides.
     * A null header is what every failure mode above collapses to, and the floor is what it earns.
     */
    private fun assertDefaultContext(openStream: () -> InputStream?) {
        val header = GgufHeaderReader.read(openStream)
        assertNull(header)
        assertEquals(DEFAULT_CONTEXT_TOKENS, ContextSizePolicy.choose(header, Long.MAX_VALUE, MODEL_SIZE))
    }

    /** A header that would earn more than the floor, so a null one is what the assertions catch. */
    private fun header() = GgufHeader(
        architecture = "llama",
        blockCount = 24L,
        embeddingLength = 1024L,
        headCount = 16L,
        headCountKv = 8L,
        keyLength = 64L,
        valueLength = 64L,
        contextLength = 32768L,
    )

    /** Opens fine and then fails, which is the case a null-check on the opener would not cover. */
    private class ThrowingStream : InputStream() {
        override fun read(): Int = throw IOException("device is gone")
    }

    private companion object {
        const val MODEL_SIZE = 64L * 1024 * 1024
    }
}
