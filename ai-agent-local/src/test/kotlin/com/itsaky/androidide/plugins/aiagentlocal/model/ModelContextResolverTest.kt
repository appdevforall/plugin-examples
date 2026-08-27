package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.DEFAULT_CONTEXT_TOKENS
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the fail-open contract: no way of failing to read a header may propagate out of [resolve],
 * because the caller is a model load that should proceed at the default context instead of aborting.
 */
class ModelContextResolverTest {

    @Test
    fun givenAnOpenerThatThrows_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve {
            throw IOException("permission denied")
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAnOpenerReturningNoStream_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve { null }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAStreamThatIsNotGguf_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve {
            "not a model file".byteInputStream()
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAStreamThatThrowsMidRead_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = resolve { ThrowingStream() }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenUnknownFreeMemory_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(null, MODEL_SIZE) {
            "not a model file".byteInputStream()
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
    }

    @Test
    fun givenUnknownModelSize_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(Long.MAX_VALUE, null) {
            "not a model file".byteInputStream()
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
    }

    /** [ModelContextResolver.resolve] with room to spare, so only the header decides the answer. */
    private fun resolve(openStream: () -> InputStream?) =
        ModelContextResolver.resolve(Long.MAX_VALUE, MODEL_SIZE, openStream)

    /** Opens fine and then fails, which is the case a null-check on the opener would not cover. */
    private class ThrowingStream : InputStream() {
        override fun read(): Int = throw IOException("device is gone")
    }

    private companion object {
        const val MODEL_SIZE = 64L * 1024 * 1024
    }
}
