package com.itsaky.androidide.plugins.aiassistant.memory

import com.itsaky.androidide.plugins.aiassistant.util.GgufHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the memory arithmetic behind the pre-flight warning.
 *
 * The figures reach the user, so they are asserted exactly rather than as ranges: a wrong KV-cache
 * formula would still produce a plausible-looking dialog.
 */
class ModelMemoryEstimatorTest {

    private val megabyte = 1024L * 1024
    private val computeBuffer = 256 * megabyte

    /** gemma-3-1b: 26 layers, 1152 wide, 4 heads, 1 kv head — so a 288-wide kv projection. */
    private val gemma = GgufHeader(
        architecture = "gemma3",
        blockCount = 26,
        embeddingLength = 1152,
        headCount = 4,
        headCountKv = 1,
    )

    @Test
    fun givenAModelShape_whenEstimated_thenTheKvCacheIsSizedForTheFullContext() {
        val fileSize = 800 * megabyte
        // A literal: restating the formula asserts only that the code agrees with itself.
        val expectedKvCache = 122_683_392L

        val estimate = ModelMemoryEstimator.estimate(fileSize, gemma)

        assertEquals(fileSize, estimate?.loadBytes)
        assertEquals(expectedKvCache + computeBuffer, estimate?.runBytes)
        assertTrue(estimate?.fromHeader == true)
    }

    @Test
    fun givenAModelWithoutGroupedQueryAttention_whenEstimated_thenEveryHeadIsCached() {
        val mha = gemma.copy(headCountKv = null)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, mha)

        // One kv head per attention head: four times gemma's 122,683,392-byte cache.
        assertEquals(490_733_568L + computeBuffer, estimate?.runBytes)
    }

    @Test
    fun givenADeclaredKeyAndValueWidth_whenEstimated_thenTheyAreUsedOverTheHeadQuotient() {
        // gemma-3 declares 256, not the 288 embedding / heads implies — a 12% overstatement.
        val declared = gemma.copy(keyLength = 256, valueLength = 256)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, declared)

        // 2 x 26 x 4096 x 1 x (256 + 256).
        assertEquals(109_051_904L + computeBuffer, estimate?.runBytes)
    }

    @Test
    fun givenOnlyOneOfTheTwoWidthsDeclared_whenEstimated_thenTheOtherStillFallsBack() {
        val halfDeclared = gemma.copy(keyLength = 256)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, halfDeclared)

        // 2 x 26 x 4096 x 1 x (256 declared key + 288 derived value).
        assertEquals(115_867_648L + computeBuffer, estimate?.runBytes)
    }

    @Test
    fun givenAZeroedKeyWidth_whenEstimated_thenTheDerivedWidthIsUsedInstead() {
        val corrupt = gemma.copy(keyLength = 0, valueLength = 0)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, corrupt)

        assertEquals(122_683_392L + computeBuffer, estimate?.runBytes)
    }

    @Test
    fun givenNoHeader_whenEstimated_thenItFallsBackToAShareOfTheFileSize() {
        val fileSize = 4096L * megabyte

        val estimate = ModelMemoryEstimator.estimate(fileSize, header = null)

        assertEquals(fileSize, estimate?.loadBytes)
        assertEquals(fileSize / 4, estimate?.runBytes)
        assertFalse(estimate?.fromHeader == true)
    }

    @Test
    fun givenASmallModelAndNoHeader_whenEstimated_thenTheRuntimeFloorStillApplies() {
        // A quarter of a 300 MB file is nowhere near enough for a KV cache at full context.
        val estimate = ModelMemoryEstimator.estimate(300 * megabyte, header = null)

        assertEquals(256 * megabyte, estimate?.runBytes)
    }

    @Test
    fun givenAnIncompleteHeader_whenEstimated_thenItFallsBackRatherThanGuessing() {
        val partial = gemma.copy(embeddingLength = null)

        val estimate = ModelMemoryEstimator.estimate(4096L * megabyte, partial)

        assertEquals(1024 * megabyte, estimate?.runBytes)
        assertFalse(estimate?.fromHeader == true)
    }

    @Test
    fun givenAZeroedHeaderValue_whenEstimated_thenItFallsBackInsteadOfDividingByZero() {
        val corrupt = gemma.copy(headCount = 0)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, corrupt)

        assertEquals(256 * megabyte, estimate?.runBytes)
        assertFalse(estimate?.fromHeader == true)
    }

    @Test
    fun givenAnUnknownFileSize_whenEstimated_thenThereIsNoEstimate() {
        assertNull(ModelMemoryEstimator.estimate(null, gemma))
        assertNull(ModelMemoryEstimator.estimate(0L, gemma))
    }

    @Test
    fun givenAnEstimate_whenTotalled_thenItIsTheWeightsPlusTheRuntime() {
        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, gemma)!!

        assertEquals(estimate.loadBytes + estimate.runBytes, estimate.totalBytes)
    }
}
