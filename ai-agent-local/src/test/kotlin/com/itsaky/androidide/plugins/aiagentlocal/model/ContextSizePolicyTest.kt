package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.DEFAULT_CONTEXT_TOKENS
import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.MAX_CONTEXT_TOKENS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSizePolicyTest {

    /** A small GQA model: 24 layers, 8 kv heads of 64, so 2 * 24 * 8 * 128 = 49_152 B/token. */
    private fun header(
        contextLength: Long? = 32768L,
        blockCount: Long? = 24L,
        headCount: Long? = 16L,
        headCountKv: Long? = 8L,
        keyLength: Long? = 64L,
        valueLength: Long? = 64L,
    ) = GgufHeader(
        architecture = "llama",
        blockCount = blockCount,
        contextLength = contextLength,
        embeddingLength = 1024L,
        headCount = headCount,
        headCountKv = headCountKv,
        keyLength = keyLength,
        valueLength = valueLength,
    )

    private val bytesPerToken = 2L * 24L * 8L * (64L + 64L)

    /** Free RAM that affords exactly [tokens], undoing the reserve and the budget divisor. */
    private fun ramAffording(tokens: Long): Long =
        tokens * bytesPerToken * 2L + ModelMemory.RUN_BUFFER_BYTES

    @Test
    fun givenNoHeader_whenChoosing_thenFallsBackToDefault() {
        assertEquals(DEFAULT_CONTEXT_TOKENS, ContextSizePolicy.choose(null, ramAffording(100_000L)))
    }

    @Test
    fun givenNoContextLengthInHeader_whenChoosing_thenFallsBackToDefault() {
        val result = ContextSizePolicy.choose(header(contextLength = null), ramAffording(100_000L))
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenUnreadableMemory_whenChoosing_thenFallsBackToDefault() {
        assertEquals(DEFAULT_CONTEXT_TOKENS, ContextSizePolicy.choose(header(), null))
    }

    @Test
    fun givenHeaderMissingShapeValues_whenChoosing_thenFallsBackToDefault() {
        // No block count and no way to derive one: the per-token cost is unknowable.
        val result = ContextSizePolicy.choose(header(blockCount = null), ramAffording(100_000L))
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenLargeContextModelAndAmpleRam_whenChoosing_thenCapsAtMaximum() {
        val result = ContextSizePolicy.choose(header(contextLength = 32768L), ramAffording(100_000L))
        assertEquals(MAX_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenModelContextBelowFloor_whenChoosing_thenHoldsTheFloor() {
        val result = ContextSizePolicy.choose(header(contextLength = 2048L), ramAffording(100_000L))
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenModelContextBetweenFloorAndMaximum_whenChoosing_thenUsesTheModelContext() {
        val result = ContextSizePolicy.choose(header(contextLength = 8192L), ramAffording(100_000L))
        assertEquals(8192, result)
    }

    @Test
    fun givenRamBoundDevice_whenChoosing_thenReturnsRoundedAffordableContext() {
        // Affords 10_000 tokens; expect it rounded down to a multiple of 256.
        val result = ContextSizePolicy.choose(header(), ramAffording(10_000L))
        assertEquals(9984, result)
        assertTrue("must stay under the model's own context", result < 32768)
    }

    @Test
    fun givenTightRam_whenChoosing_thenNeverGoesBelowTheFloor() {
        val result = ContextSizePolicy.choose(header(), ramAffording(1_000L))
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenNoFreeMemory_whenChoosing_thenReturnsFloorRatherThanZero() {
        assertEquals(DEFAULT_CONTEXT_TOKENS, ContextSizePolicy.choose(header(), 0L))
    }

    @Test
    fun givenLessFreeRamThanTheComputeReserve_whenChoosing_thenReturnsFloor() {
        // Budget goes negative here; the floor has to absorb it rather than a negative context.
        val result = ContextSizePolicy.choose(header(), ModelMemory.RUN_BUFFER_BYTES / 2)
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenAbsurdContextLength_whenChoosing_thenCapsAtMaximumWithoutOverflow() {
        val result = ContextSizePolicy.choose(header(contextLength = Long.MAX_VALUE), ramAffording(100_000L))
        assertEquals(MAX_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenNegativeContextLength_whenChoosing_thenFallsBackToDefault() {
        val result = ContextSizePolicy.choose(header(contextLength = -1L), ramAffording(100_000L))
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenAbsurdShapeValues_whenChoosing_thenFallsBackToDefaultWithoutOverflow() {
        val result = ContextSizePolicy.choose(
            header(blockCount = Long.MAX_VALUE, keyLength = Long.MAX_VALUE),
            ramAffording(100_000L),
        )
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenAQuantizableModel_whenChoosingTheCacheType_thenPicksQ8_0() {
        assertEquals(KvCacheType.Q8_0, ContextSizePolicy.chooseKvCache(header()))
    }

    @Test
    fun givenAHeadWidthQ8_0CannotHold_whenChoosingTheCacheType_thenFallsBackToF16() {
        assertEquals(KvCacheType.F16, ContextSizePolicy.chooseKvCache(header(keyLength = 80L)))
    }

    @Test
    fun givenNoHeader_whenChoosingTheCacheType_thenFallsBackToF16() {
        assertEquals(KvCacheType.F16, ContextSizePolicy.chooseKvCache(null))
    }

    @Test
    fun givenTheSameRam_whenChoosingUnderQ8_0_thenAffordsNearlyTwiceTheContext() {
        // The RAM that buys 5_000 f16 tokens buys 9_411 q8_0 ones, rounded down to whole blocks.
        val ram = ramAffording(5_000L)
        assertEquals(4864, ContextSizePolicy.choose(header(), ram, KvCacheType.F16))
        assertEquals(9216, ContextSizePolicy.choose(header(), ram, KvCacheType.Q8_0))
    }

    @Test
    fun givenAModelContextBelowWhatQ8_0Affords_whenChoosing_thenTheModelStillCaps() {
        val result = ContextSizePolicy.choose(header(contextLength = 8192L), ramAffording(5_000L), KvCacheType.Q8_0)
        assertEquals(8192, result)
    }

    @Test
    fun givenTightRamUnderQ8_0_whenChoosing_thenNeverGoesBelowTheFloor() {
        val result = ContextSizePolicy.choose(header(), ramAffording(1_000L), KvCacheType.Q8_0)
        assertEquals(DEFAULT_CONTEXT_TOKENS, result)
    }

    @Test
    fun givenAnyInputs_whenChoosing_thenResultStaysWithinTheDeclaredBounds() {
        val contexts = listOf(null, -1L, 0L, 512L, 4096L, 8192L, 32768L, Long.MAX_VALUE)
        val memories = listOf(null, 0L, 1L, ModelMemory.RUN_BUFFER_BYTES, ramAffording(50_000L), Long.MAX_VALUE)
        for (context in contexts) {
            for (memory in memories) {
                for (kvType in KvCacheType.entries) {
                    val result = ContextSizePolicy.choose(header(contextLength = context), memory, kvType)
                    assertTrue(
                        "context=$context memory=$memory kv=$kvType gave $result",
                        result in DEFAULT_CONTEXT_TOKENS..MAX_CONTEXT_TOKENS,
                    )
                    assertEquals("must be a whole number of 256-token blocks", 0, result % 256)
                }
            }
        }
    }
}
