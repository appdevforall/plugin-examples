package com.itsaky.androidide.plugins.aiassistant.memory

import com.itsaky.androidide.plugins.aiassistant.memory.ModelMemoryGate.Severity
import com.itsaky.androidide.plugins.aiassistant.memory.ModelMemoryGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the decision to warn, including its boundaries — one byte either side of them is the
 * difference between an interrupted user and a crash.
 */
class ModelMemoryGateTest {

    private val megabyte = 1024L * 1024

    /** 800 MB of weights that need 400 MB of working memory: 1200 MB in total. */
    private val estimate = MemoryEstimate(
        loadBytes = 800 * megabyte,
        runBytes = 400 * megabyte,
        fromHeader = true,
    )

    private fun severityAt(availableBytes: Long): Severity? =
        (ModelMemoryGate.evaluate(estimate, availableBytes) as? Verdict.Risky)?.severity

    @Test
    fun givenRoomForEverything_whenEvaluated_thenTheUserIsNotInterrupted() {
        assertEquals(Verdict.Safe, ModelMemoryGate.evaluate(estimate, 2048 * megabyte))
    }

    @Test
    fun givenExactlyEnough_whenEvaluated_thenItIsSafe() {
        assertEquals(Verdict.Safe, ModelMemoryGate.evaluate(estimate, estimate.totalBytes))
    }

    @Test
    fun givenOneByteTooLittle_whenEvaluated_thenThrashingIsTheRisk() {
        assertEquals(Severity.TIGHT, severityAt(estimate.totalBytes - 1))
    }

    @Test
    fun givenRoomForTheRuntimeButNotTheWeights_whenEvaluated_thenThrashingIsTheRisk() {
        assertEquals(Severity.TIGHT, severityAt(600 * megabyte))
    }

    @Test
    fun givenNotEvenRoomForTheRuntime_whenEvaluated_thenFailureIsExpected() {
        assertEquals(Severity.INSUFFICIENT, severityAt(estimate.runBytes - 1))
    }

    @Test
    fun givenExactlyEnoughForTheRuntime_whenEvaluated_thenItIsOnlyTight() {
        assertEquals(Severity.TIGHT, severityAt(estimate.runBytes))
    }

    @Test
    fun givenNoEstimate_whenEvaluated_thenNothingIsClaimed() {
        assertEquals(Verdict.Unknown, ModelMemoryGate.evaluate(null, 2048 * megabyte))
    }

    @Test
    fun givenUnreadableMemory_whenEvaluated_thenItFailsOpenRatherThanWarning() {
        // A device we can't measure must not be told its model won't fit.
        assertEquals(Verdict.Unknown, ModelMemoryGate.evaluate(estimate, null))
    }

    @Test
    fun givenNoFreeMemoryAtAll_whenEvaluated_thenThatIsARealReadingAndNotUnknown() {
        assertEquals(Severity.INSUFFICIENT, severityAt(0L))
    }

    @Test
    fun givenARiskyModel_whenEvaluated_thenTheVerdictCarriesTheFiguresToShow() {
        val verdict = ModelMemoryGate.evaluate(estimate, 600 * megabyte) as Verdict.Risky

        assertEquals(estimate, verdict.estimate)
        assertEquals(600 * megabyte, verdict.availableBytes)
    }
}
