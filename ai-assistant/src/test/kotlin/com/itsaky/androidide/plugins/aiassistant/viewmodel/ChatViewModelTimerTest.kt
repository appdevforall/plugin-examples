package com.itsaky.androidide.plugins.aiassistant.models

import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for ChatViewModel timer mechanism for step progress updates.
 * Tests the core logic of timer state transitions and Executing state properties.
 */
class ChatViewModelTimerTest {

    @Test
    fun testExecutingStateTimerInitialization() {
        val beforeCreation = System.currentTimeMillis()
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Processing",
            startTime = System.currentTimeMillis(),
            elapsedMillis = 0
        )
        val afterCreation = System.currentTimeMillis()

        assertEquals(0, state.currentStepIndex)
        assertEquals(5, state.totalSteps)
        assertEquals("Processing", state.description)
        assertEquals(0, state.elapsedMillis)
        assertTrue(state.startTime >= beforeCreation)
        assertTrue(state.startTime <= afterCreation + 100)
    }

    @Test
    fun testExecutingStateTimerUpdating() {
        val startTime = System.currentTimeMillis() - 1000
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Processing",
            startTime = startTime,
            elapsedMillis = 1000
        )

        val updatedState = state.copy(elapsedMillis = 2000)

        assertEquals(state.startTime, updatedState.startTime)
        assertEquals(1000, state.elapsedMillis)
        assertEquals(2000, updatedState.elapsedMillis)
        assertEquals(state.description, updatedState.description)
    }

    @Test
    fun testExecutingStateWithDefaultStartTime() {
        val beforeCreation = System.currentTimeMillis()
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Processing"
        )
        val afterCreation = System.currentTimeMillis()

        assertTrue(state.startTime >= beforeCreation)
        assertTrue(state.startTime <= afterCreation + 1000)  // Allow 1 second buffer
    }

    @Test
    fun testExecutingStateWithCustomStartTime() {
        val customTime = System.currentTimeMillis() - 60000
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Processing",
            startTime = customTime
        )

        assertEquals(customTime, state.startTime)
    }

    @Test
    fun testExecutingStateWithDefaultElapsedMillis() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Processing"
        )

        assertEquals(0, state.elapsedMillis)
    }

    @Test
    fun testExecutingStateWithCustomElapsedMillis() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 5,
            description = "Processing",
            elapsedMillis = 5000
        )

        assertEquals(5000, state.elapsedMillis)
    }

    @Test
    fun testFormattedProgressFormat() {
        val state = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = 3,
            description = "Loading model"
        )

        val formattedProgress = state.formattedProgress
        assertTrue(formattedProgress.contains("Step"))
        assertTrue(formattedProgress.contains("of"))
        assertTrue(formattedProgress.contains("Loading model"))
    }

    @Test
    fun testFormattedTimingFormat() {
        val state = AgentState.Executing(
            currentStepIndex = 1,
            totalSteps = 3,
            description = "Processing",
            elapsedMillis = 2000
        )

        val formattedTiming = state.formattedTiming
        assertTrue(formattedTiming.startsWith("("))
        assertTrue(formattedTiming.endsWith(")"))
        assertTrue(formattedTiming.contains("of"))
    }

    @Test
    fun testFormattedProgressMultipleSteps() {
        val state1 = AgentState.Executing(0, 5, "First")
        val state2 = AgentState.Executing(2, 5, "Middle")
        val state3 = AgentState.Executing(4, 5, "Last")

        assertEquals("Step 1 of 5: First", state1.formattedProgress)
        assertEquals("Step 3 of 5: Middle", state2.formattedProgress)
        assertEquals("Step 5 of 5: Last", state3.formattedProgress)
    }

    @Test
    fun testTimerStateTransitions() {
        val startTime = System.currentTimeMillis()

        // Initial state
        val state1 = AgentState.Executing(0, 5, "Start", startTime, 0)
        assertEquals(0, state1.elapsedMillis)

        // Simulating timer update
        val state2 = state1.copy(elapsedMillis = 500)
        assertEquals(500, state2.elapsedMillis)

        // Another update
        val state3 = state2.copy(elapsedMillis = 1000)
        assertEquals(1000, state3.elapsedMillis)
        assertEquals(state1.startTime, state3.startTime)
    }
}
