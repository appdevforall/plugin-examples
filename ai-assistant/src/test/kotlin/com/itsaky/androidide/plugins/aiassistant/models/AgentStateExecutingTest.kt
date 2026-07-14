package com.itsaky.androidide.plugins.aiassistant.models

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

/**
 * Unit tests for AgentState.Executing with step progress and timing.
 */
class AgentStateExecutingTest {

    @Test
    fun testExecutingStateCreation() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file"
        )

        assertEquals(0, state.currentStepIndex)
        assertEquals(5, state.totalSteps)
        assertEquals("Reading file", state.description)
        assertNotNull(state.startTime)
        assertEquals(0, state.elapsedMillis)
    }

    @Test
    fun testFormattedProgressFirstStep() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file"
        )

        assertEquals("Step 1 of 5: Reading file", state.formattedProgress)
    }

    @Test
    fun testFormattedProgressMiddleStep() {
        val state = AgentState.Executing(
            currentStepIndex = 2,
            totalSteps = 5,
            description = "Processing data"
        )

        assertEquals("Step 3 of 5: Processing data", state.formattedProgress)
    }

    @Test
    fun testFormattedProgressLastStep() {
        val state = AgentState.Executing(
            currentStepIndex = 4,
            totalSteps = 5,
            description = "Finalizing"
        )

        assertEquals("Step 5 of 5: Finalizing", state.formattedProgress)
    }

    @Test
    fun testFormattedTimingWithZeroElapsed() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 0
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("0.0s"))
        assertTrue(timing.contains("of"))
    }

    @Test
    fun testFormattedTimingWithMilliseconds() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 500
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("s"))
        assertTrue(timing.contains("of"))
        // Should show 0.5s or similar
    }

    @Test
    fun testFormattedTimingWithSeconds() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 2500
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("s"))
        assertTrue(timing.contains("of"))
    }

    @Test
    fun testFormattedTimingWithMinutesAndSeconds() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 65000  // 1 minute 5 seconds
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("m"))
        assertTrue(timing.contains("s"))
        assertTrue(timing.contains("of"))
    }

    @Test
    fun testFormattedTimingEstimateWithMultipleSteps() {
        // Simulate 2 steps completed out of 5
        // If 2000ms elapsed for 2 steps, avg is 1000ms per step
        // Estimated total should be around 5000ms
        val state = AgentState.Executing(
            currentStepIndex = 1,  // 2nd step (0-indexed)
            totalSteps = 5,
            description = "Processing",
            elapsedMillis = 2000
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("of"))
        // Should show estimated total of ~5 seconds
    }

    @Test
    fun testFormattedTimingNegativeElapsed() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = -100
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("0.0s"))
    }

    @Test
    fun testExecutingStateCopyWithNewElapsed() {
        val originalState = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 0
        )

        val copiedState = originalState.copy(elapsedMillis = 1000)

        assertEquals(0, originalState.currentStepIndex)
        assertEquals(5, originalState.totalSteps)
        assertEquals("Reading file", originalState.description)
        assertEquals(0, originalState.elapsedMillis)

        assertEquals(0, copiedState.currentStepIndex)
        assertEquals(5, copiedState.totalSteps)
        assertEquals("Reading file", copiedState.description)
        assertEquals(1000, copiedState.elapsedMillis)
    }

    @Test
    fun testExecutingStateWithCustomStartTime() {
        val customStartTime = System.currentTimeMillis() - 10000
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            startTime = customStartTime,
            elapsedMillis = 5000
        )

        assertEquals(customStartTime, state.startTime)
        assertEquals(5000, state.elapsedMillis)
    }

    @Test
    fun testFormattedProgressWithSpecialCharacters() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 2,
            description = "Read: app/src/main.kt"
        )

        assertEquals("Step 1 of 2: Read: app/src/main.kt", state.formattedProgress)
    }

    @Test
    fun testFormattedTimingFormat() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 10,
            description = "Processing",
            elapsedMillis = 1500
        )

        val timing = state.formattedTiming
        // Should match pattern like "(1.5s of 15.0s)"
        assertTrue(timing.startsWith("("))
        assertTrue(timing.endsWith(")"))
        assertTrue(timing.contains("of"))
    }

    @Test
    fun testFormattedTimingWith60Seconds() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 60000  // Exactly 1 minute
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("m"))
        assertTrue(timing.contains("s"))
    }

    @Test
    fun testFormattedTimingWith3Minutes() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Reading file",
            elapsedMillis = 180000  // 3 minutes
        )

        val timing = state.formattedTiming
        assertTrue(timing.contains("3m"))
    }
}
