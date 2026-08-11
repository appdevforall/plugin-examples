package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AiCorePluginTest {

    private lateinit var mockLogger: PluginLogger
    private lateinit var mockContext: PluginContext

    @Before
    fun setup() {
        // AiCorePlugin publishes its service into the process-global SharedServices,
        // so start from a clean slate to avoid leakage between tests.
        SharedServices.clear()
        mockLogger = mockk(relaxed = true)
        mockContext = mockk(relaxed = true) {
            every { logger } returns mockLogger
        }
    }

    @After
    fun tearDown() {
        SharedServices.clear()
    }

    @Test
    fun testPluginInitialization() {
        val plugin = AiCorePlugin()
        val result = plugin.initialize(mockContext)

        assertTrue(result)
        verify { mockLogger.info("AiCorePlugin: Plugin initialized successfully") }
    }

    @Test
    fun testPluginActivation() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        val result = plugin.activate()

        assertTrue(result)
        verify { mockLogger.info("AiCorePlugin: Activating plugin") }
    }

    @Test
    fun testServiceRegistration() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        // activate() registers the LlmInferenceService into SharedServices for other plugins.
        val service = SharedServices.get(LlmInferenceService::class.java)
        assertNotNull(service)
    }

    @Test
    fun givenActivated_whenInspectingBackends_thenNoneAreOwnedByAiCore() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        // AI Core contributes no backend of its own; ai-backend-local and ai-backend-gemini
        // register themselves. A backend appearing here means that split regressed.
        val service = SharedServices.get(LlmInferenceService::class.java)
        assertNotNull(service)
        assertTrue(service!!.getAvailableBackends().isEmpty())
    }
}
