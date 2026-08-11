package com.itsaky.androidide.plugins.ailocal

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class LocalLlmBackendTest {

    private lateinit var backend: LocalLlmBackend

    @Before
    fun setup() {
        backend = LocalLlmBackend(mockk(relaxed = true))
    }

    @Test
    fun testBackendId() {
        assertEquals("local", backend.getId())
    }

    @Test
    fun testBackendName() {
        assertEquals("Local LLM", backend.getName())
    }

    @Test
    fun testIsAvailableWhenNotInitialized() {
        // Backend requires model initialization, should be false initially
        assertFalse(backend.isAvailable())
    }

    @Test
    fun givenTheToolsPath_whenChecked_thenThisBackendOverridesItRatherThanInheritingTheDefault() {
        // LlmBackend's default generateStreamingWithTools routes to single-turn generateStreaming
        // and DROPS the history. Losing this override turns chat into a one-shot prompt with no
        // error to explain it, which no behavioural test notices — so assert the override exists.
        val method = LocalLlmBackend::class.java.getMethod(
            "generateStreamingWithTools",
            String::class.java,
            List::class.java,
            LlmConfig::class.java,
            List::class.java,
            ToolStreamCallback::class.java,
        )

        assertEquals(LocalLlmBackend::class.java, method.declaringClass)
    }

    @Test
    fun givenTheHistoryPath_whenChecked_thenThisBackendOverridesItRatherThanInheritingTheDefault() {
        val method = LocalLlmBackend::class.java.getMethod(
            "generateStreamingWithHistory",
            List::class.java,
            String::class.java,
            LlmConfig::class.java,
            StreamCallback::class.java,
        )

        assertEquals(LocalLlmBackend::class.java, method.declaringClass)
    }

    @Test
    fun givenNoModelConfigured_whenAskedForConfigSpecs_thenItAdvertisesTheModelFilePicker() {
        val specs = backend.getConfigSpecs()

        assertEquals(1, specs.size)
        assertEquals("local_llm_model_path", specs[0].key)
        assertEquals(ConfigFieldType.FILE_PICKER, specs[0].type)
        assertTrue(specs[0].required)
    }

    @Test
    fun testGenerateReturnsErrorWhenNotAvailable() {
        val config = LlmConfig("local")
        val future = backend.generate("Test prompt", config)
        val response = future.get()

        assertFalse(response.success)
        assertNotNull(response.error)
        // With no model configured, generate() fails fast before any native work.
        assertTrue(response.error!!.contains("No model configured"))
    }
}
