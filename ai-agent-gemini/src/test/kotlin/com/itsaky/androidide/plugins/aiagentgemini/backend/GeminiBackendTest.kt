package com.itsaky.androidide.plugins.aiagentgemini.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiBackendTest {

    private lateinit var backend: GeminiBackend

    @Before
    fun setup() {
        backend = GeminiBackend(mockk(relaxed = true))
    }

    @Test
    fun givenTheBackend_whenAskedForItsIdentity_thenItRegistersAsGemini() {
        assertEquals("gemini", backend.getId())
        assertEquals("Gemini API", backend.getName())
    }

    @Test
    fun givenTheBackend_whenAskedForItsCapabilities_thenItDeclaresHistoryButNotToolCalling() {
        // Dropping HistoryCapableBackend compiles and silently turns chat into one-shot prompting.
        val declared: LlmBackend = backend

        assertTrue(declared is HistoryCapableBackend)
        assertFalse(declared is ToolCallingBackend)
    }
}
