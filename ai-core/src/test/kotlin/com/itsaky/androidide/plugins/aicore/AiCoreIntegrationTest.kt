package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * Integration test demonstrating the complete AI Core Plugin workflow.
 */
class AiCoreIntegrationTest {

    private lateinit var plugin: AiCorePlugin
    private lateinit var context: PluginContext

    @Before
    fun setup() {
        // The plugin registers into the process-global SharedServices; clear it so the
        // workflow starts from a known-empty state and can't inherit another test's service.
        SharedServices.clear()
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        context = mockk(relaxed = true) {
            every { logger } returns mockLogger
        }
        plugin = AiCorePlugin()
    }

    @After
    fun teardown() {
        // Avoid plugin.dispose() here: it tears down the native run loop, which is not
        // available in a JVM unit test. Clearing SharedServices is enough for isolation.
        SharedServices.clear()
    }

    @Test
    fun testCompletePluginWorkflow() {
        // Step 1: Initialize plugin
        val initSuccess = plugin.initialize(context)
        assertTrue("Plugin initialization should succeed", initSuccess)

        // Step 2: Activate plugin (registers service and backends)
        val activateSuccess = plugin.activate()
        assertTrue("Plugin activation should succeed", activateSuccess)

        // Step 3: Retrieve LlmInferenceService published to SharedServices
        val service = SharedServices.get(LlmInferenceService::class.java)
        assertNotNull("LlmInferenceService should be registered", service)

        // Step 4: Verify both backends (local + gemini) are registered
        val backends = service!!.getAvailableBackends()
        assertEquals("Should have 2 backends", 2, backends.size)
        val backendIds = backends.map { it.getId() }.toSet()
        assertTrue("Local backend should be registered", backendIds.contains("local"))
        assertTrue("Gemini backend should be registered", backendIds.contains("gemini"))

        // Step 5: Check backend availability (no model loaded / no API key)
        assertFalse("Local backend should not be available", service.isBackendAvailable("local"))

        // Step 6: Attempt generation with an unavailable backend
        val config = LlmConfig("local")
        config.temperature = 0.7f
        config.maxTokens = 100

        val future = service.generateCompletion("Write a hello world function", config)
        val response = future.get()

        assertFalse("Response should fail (backend unavailable)", response.success)
        assertNotNull("Error message should be present", response.error)
        assertTrue("Error should mention availability",
            response.error!!.contains("not available"))

        // Step 7: Deactivate plugin
        val deactivateSuccess = plugin.deactivate()
        assertTrue("Plugin deactivation should succeed", deactivateSuccess)

        // Step 8: Verify backends unregistered after deactivation
        assertNull("Local backend should be unregistered", service.getBackend("local"))
        assertNull("Gemini backend should be unregistered", service.getBackend("gemini"))
    }
}
