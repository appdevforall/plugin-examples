package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.util.concurrent.CompletableFuture

class LlmInferenceServiceImplTest {

    private lateinit var service: LlmInferenceServiceImpl

    @Before
    fun setup() {
        // No AI Assistant PluginContext registered → no stored preference, so AUTO resolves
        // via the LOCAL default + availability fallback rather than a value left by another test.
        SharedServices.clear()
        service = LlmInferenceServiceImpl()
    }

    private fun mockBackend(
        backendId: String,
        available: Boolean = true,
        text: String = "generated",
    ) = mockk<LlmBackend> {
        every { getId() } returns backendId
        every { getName() } returns backendId
        every { isAvailable() } returns available
        every { generate(any(), any()) } returns CompletableFuture.completedFuture(
            LlmResponse.success(text, 1, 1)
        )
    }

    @Test
    fun testRegisterBackend() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)

        val backends = service.getAvailableBackends()
        assertEquals(1, backends.size)
        assertEquals("test-backend", backends[0].getId())
    }

    @Test
    fun testGetBackend() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)
        val backend = service.getBackend("test-backend")

        assertNotNull(backend)
        assertEquals("test-backend", backend!!.getId())
    }

    @Test
    fun testUnregisterBackend() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)
        service.unregisterBackend("test-backend")

        val backend = service.getBackend("test-backend")
        assertNull(backend)
    }

    @Test
    fun testIsBackendAvailable() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
        }

        service.registerBackend(mockBackend)
        assertTrue(service.isBackendAvailable("test-backend"))
        assertFalse(service.isBackendAvailable("nonexistent"))
    }

    @Test
    fun testGenerateCompletion() {
        val mockBackend = mockk<LlmBackend> {
            every { getId() } returns "test-backend"
            every { getName() } returns "Test Backend"
            every { isAvailable() } returns true
            every { generate(any(), any()) } returns CompletableFuture.completedFuture(
                LlmResponse.success("Generated text", 10, 100)
            )
        }

        service.registerBackend(mockBackend)
        val config = LlmConfig("test-backend")
        val future = service.generateCompletion("Test prompt", config)
        val response = future.get()

        assertTrue(response.success)
        assertEquals("Generated text", response.text)
    }

    @Test
    fun testAutoPrefersLocalWhenAvailable() {
        service.registerBackend(mockBackend("local", text = "from local"))
        service.registerBackend(mockBackend("gemini", text = "from gemini"))

        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertTrue(response.success)
        assertEquals("from local", response.text)
        assertEquals("local", config.backendId) // sentinel normalized to the resolved id
    }

    @Test
    fun testAutoFallsBackToAvailableBackendWhenSelectedMissing() {
        // Selection defaults to LOCAL, but only Gemini is registered/available.
        service.registerBackend(mockBackend("gemini", text = "from gemini"))

        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertTrue(response.success)
        assertEquals("from gemini", response.text)
        assertEquals("gemini", config.backendId)
    }

    @Test
    fun testAutoFailsWhenNoBackendAvailable() {
        val config = LlmConfig(AiBackend.AUTO)
        val response = service.generateCompletion("prompt", config).get()

        assertFalse(response.success)
    }
}
