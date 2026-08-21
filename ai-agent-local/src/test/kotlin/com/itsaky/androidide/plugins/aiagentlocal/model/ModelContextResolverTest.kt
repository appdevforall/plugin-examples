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
        val resolved = ModelContextResolver.resolve(Long.MAX_VALUE) {
            throw IOException("permission denied")
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAnOpenerReturningNoStream_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(Long.MAX_VALUE) { null }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAStreamThatIsNotGguf_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(Long.MAX_VALUE) {
            "not a model file".byteInputStream()
        }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenAStreamThatThrowsMidRead_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(Long.MAX_VALUE) { ThrowingStream() }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
        assertNull(resolved.header)
    }

    @Test
    fun givenUnknownFreeMemory_whenResolving_thenReturnsTheDefaultContext() {
        val resolved = ModelContextResolver.resolve(null) { "not a model file".byteInputStream() }
        assertEquals(DEFAULT_CONTEXT_TOKENS, resolved.contextTokens)
    }

    @Test
    fun givenAnUnreadableHeader_whenResolving_thenTheFallbackSizeIsTheDefaultToo() {
        // No header means f16, so the native fallback has nothing shorter to drop to.
        val resolved = ModelContextResolver.resolve(Long.MAX_VALUE) { null }
        assertEquals(KvCacheType.F16, resolved.kvType)
        assertEquals(resolved.contextTokens, resolved.fallbackContextTokens)
    }

    /** Opens fine and then fails, which is the case a null-check on the opener would not cover. */
    private class ThrowingStream : InputStream() {
        override fun read(): Int = throw IOException("device is gone")
    }
}
