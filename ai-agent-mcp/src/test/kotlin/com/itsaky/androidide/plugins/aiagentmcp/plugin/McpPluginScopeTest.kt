package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Whether [McpPlugin] leaves a coroutine scope running past the lifecycle edge that ended it.
 *
 * The scope carries the cold-start `tools/list` refresh, which registers sessions in
 * `McpConnections` and fills `McpToolCatalog`. Left running past `deactivate`, it repopulates both
 * straight after they were cleared, leaving sockets nothing can reach — and a host that activates
 * twice would orphan the first scope entirely.
 */
class McpPluginScopeTest {

    private val prefs = FakeSharedPreferences()
    private lateinit var context: PluginContext
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns prefs
        plugin = McpPlugin()
        plugin.initialize(context)
    }

    @After
    fun tearDown() {
        plugin.dispose()
    }

    @Test
    fun givenAnActivatedPlugin_whenActivatedAgain_thenThePreviousScopeIsCancelled() {
        plugin.activate()
        val first = requireNotNull(plugin.scopeJob)

        plugin.activate()

        val second = requireNotNull(plugin.scopeJob)
        assertTrue("the orphaned scope must not outlive the activation", first.isCancelled)
        assertNotSame("a second activation gets a scope of its own", first, second)
        assertScopeUsable(second)
    }

    @Test
    fun givenAnActivatedPlugin_whenDeactivated_thenTheScopeIsCancelled() {
        plugin.activate()
        val job = requireNotNull(plugin.scopeJob)

        plugin.deactivate()

        assertTrue("an in-flight refresh must not survive deactivation", job.isCancelled)
    }

    @Test
    fun givenAnActivatedPlugin_whenDisposed_thenTheScopeIsCancelled() {
        plugin.activate()
        val job = requireNotNull(plugin.scopeJob)

        plugin.dispose()

        assertTrue("dispose must leave nothing running", job.isCancelled)
    }

    @Test
    fun givenADeactivatedPlugin_whenActivatedAgain_thenItGetsALiveScope() {
        plugin.activate()
        plugin.deactivate()

        plugin.activate()

        assertScopeUsable(requireNotNull(plugin.scopeJob))
    }

    /**
     * Asserts a scope can still take work, which a cancelled one cannot.
     * @param job the activation scope's job.
     */
    private fun assertScopeUsable(job: Job) {
        assertFalse("the current activation's scope must be live", job.isCancelled)
        assertTrue("the current activation's scope must accept work", job.isActive)
    }
}
