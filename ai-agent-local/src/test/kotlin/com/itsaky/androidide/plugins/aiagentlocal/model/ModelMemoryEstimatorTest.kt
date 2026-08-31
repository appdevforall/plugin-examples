package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.DEFAULT_CONTEXT_TOKENS
import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy.MAX_CONTEXT_TOKENS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two properties the pre-flight warning depends on: that it prices the KV cache at the
 * floor rather than at a context derived from the free RAM it is then compared against (ADFA-5187),
 * and that no context can wrap the product and turn "does not fit" into "fits".
 */
class ModelMemoryEstimatorTest {

    @Test
    fun givenASelectedModel_whenEstimating_thenTheCacheIsPricedAtTheFloorContext() {
        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, header())!!

        assertEquals(PER_TOKEN * DEFAULT_CONTEXT_TOKENS + ModelMemory.RUN_BUFFER_BYTES, selection.runBytes)
    }

    @Test
    fun givenARoomyDevice_whenEstimatingForSelection_thenTheGrantedContextDoesNotChangeThePrice() {
        // What the circularity looked like: the policy's answer fed back into the estimate that is
        // then judged against the same free RAM, so a larger granted context trips its own warning.
        val header = header()
        val granted = ContextSizePolicy.choose(header, ROOMY_DEVICE_BYTES, MODEL_SIZE)
        val ramDerived = ModelMemoryEstimator.estimate(MODEL_SIZE, header, granted)!!

        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, header)!!

        assertEquals(MAX_CONTEXT_TOKENS, granted)
        assertNotEquals(ramDerived.runBytes, selection.runBytes)
        assertEquals(PER_TOKEN * DEFAULT_CONTEXT_TOKENS + ModelMemory.RUN_BUFFER_BYTES, selection.runBytes)
    }

    @Test
    fun givenAContextAboveTheCeiling_whenEstimating_thenItIsClampedRatherThanWrapped() {
        // Shape values at their ceilings put per-token at 2^44, so an unclamped Int multiplier
        // overflows to a negative cache and MemoryEstimate.totalBytes waves the model through.
        val widest = header(blockCount = 1L shl 10, headCount = 1L shl 12, headCountKv = 1L shl 12, width = 1L shl 20)
        val atCeiling = ModelMemoryEstimator.estimate(MODEL_SIZE, widest, MAX_CONTEXT_TOKENS)!!

        val absurd = ModelMemoryEstimator.estimate(MODEL_SIZE, widest, Int.MAX_VALUE)!!

        assertTrue(absurd.runBytes > 0L)
        assertTrue(absurd.totalBytes > 0L)
        assertEquals(atCeiling.runBytes, absurd.runBytes)
    }

    @Test
    fun givenNoHeader_whenEstimatingForSelection_thenItFallsBackToTheSizeHeuristic() {
        val selection = ModelMemoryEstimator.estimateForSelection(MODEL_SIZE, null)!!

        assertEquals(MODEL_SIZE, selection.loadBytes)
        assertTrue(!selection.fromHeader)
    }

    private fun header(
        blockCount: Long = 24L,
        headCount: Long = 16L,
        headCountKv: Long = 8L,
        width: Long = 64L,
    ) = GgufHeader(
        architecture = "llama",
        blockCount = blockCount,
        embeddingLength = 1024L,
        headCount = headCount,
        headCountKv = headCountKv,
        keyLength = width,
        valueLength = width,
        contextLength = 32768L,
    )

    private companion object {
        const val MODEL_SIZE = 64L * 1024 * 1024

        /** The default header's cost per cached position: 2 bytes x 24 layers x 8 kv heads x 128. */
        const val PER_TOKEN = 2L * 24 * 8 * (64 + 64)

        /** Enough free RAM that the policy lands on its ceiling rather than on the affordable term. */
        const val ROOMY_DEVICE_BYTES = 8L * 1024 * 1024 * 1024
    }
}
