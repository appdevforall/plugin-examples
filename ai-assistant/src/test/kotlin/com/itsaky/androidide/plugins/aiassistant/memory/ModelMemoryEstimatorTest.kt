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
    fun givenALayerCountThatWouldWrapTheProduct_whenEstimated_thenItFallsBackInsteadOfUnderstating() {
        // 2^61 layers with 288-wide projections wraps the KV term to exactly zero.
        val crafted = gemma.copy(blockCount = 1L shl 61, keyLength = 288, valueLength = 288)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, crafted)

        assertEquals(256 * megabyte, estimate?.runBytes)
        assertFalse(estimate?.fromHeader == true)
    }

    @Test
    fun givenALayerCountThatWouldMakeTheProductNegative_whenEstimated_thenTheTotalStaysPositive() {
        // 2^49 layers with 1-wide projections lands on Long.MIN_VALUE, which reads as "it fits".
        val crafted = gemma.copy(blockCount = 1L shl 49, keyLength = 1, valueLength = 1)

        val estimate = ModelMemoryEstimator.estimate(800 * megabyte, crafted)!!

        assertTrue(estimate.totalBytes > 0L)
        assertFalse(estimate.fromHeader)
    }

    @Test
    fun givenTheLargestRealisticShape_whenEstimated_thenTheCeilingsStillAcceptIt() {
        // llama-3.1-405B: 126 layers, 16384 wide, 128 heads, 8 kv heads.
        val large = GgufHeader(
            architecture = "llama",
            blockCount = 126,
            embeddingLength = 16384,
            headCount = 128,
            headCountKv = 8,
        )

        val estimate = ModelMemoryEstimator.estimate(200L * 1024 * megabyte, large)

        assertTrue(estimate?.fromHeader == true)
    }

    @Test
    fun givenAFileSizeThatWouldWrapTheTotal_whenTotalled_thenItSaturatesInsteadOfGoingNegative() {
        // A queried SIZE column is not trustworthy, and a negative total reads as "it fits".
        val estimate = ModelMemoryEstimator.estimate(Long.MAX_VALUE, gemma)!!

        assertEquals(Long.MAX_VALUE, estimate.totalBytes)
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
