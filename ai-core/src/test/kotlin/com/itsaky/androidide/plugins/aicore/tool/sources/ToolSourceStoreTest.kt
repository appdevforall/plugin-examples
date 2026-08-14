package com.itsaky.androidide.plugins.aicore.tool.sources

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolSourceStore] and the [AgentTools] snapshot built from it — above all the
 * trap that a rebuilt router with a stale grammar forbids every contributed tool with no error.
 */
class ToolSourceStoreTest {

    private companion object {
        const val TERMINAL_TOOL = "respond"
        const val SEARCH_PLUGIN = "com.example.searchplugin"
        const val MCP = "com.itsaky.androidide.plugins.aiagentmcp"
    }

    private class BuiltInHandler(override val toolName: String) : ToolHandler {
        override val description = "built in"
        override suspend fun execute(args: Map<String, Any?>) = ToolResult.success("ok")
    }

    private val builtIns = listOf(BuiltInHandler("edit_file"), BuiltInHandler("read_file"))

    private fun toolsFrom(store: ToolSourceStore): AgentTools = AgentTools.build(
        builtInHandlers = builtIns,
        store = store,
        approvalManager = ToolApprovalManager(),
        terminalTool = TERMINAL_TOOL,
    )

    @Test
    fun givenARegisteredSource_whenTheToolSetIsRebuilt_thenRouterAndGrammarBothCarryTheTool() {
        // The grammar is the half that fails silently: a stale one masks the name out of sampling.
        val store = ToolSourceStore()
        store.register(
            FakeToolSource(SEARCH_PLUGIN, tools = listOf(contributedTool(SEARCH_PLUGIN, "code_search")))
        )

        val tools = toolsFrom(store)

        assertNotNull(
            "the router must route the contributed tool",
            tools.router.getHandler("code_search")
        )
        assertTrue(
            "the grammar must allow the contributed tool: ${tools.grammar}",
            tools.grammar.contains("code_search")
        )
    }

    @Test
    fun givenAnUnregisteredSource_whenTheToolSetIsRebuilt_thenItIsGoneFromRouterAndGrammar() {
        val store = ToolSourceStore()
        store.register(
            FakeToolSource(SEARCH_PLUGIN, tools = listOf(contributedTool(SEARCH_PLUGIN, "code_search")))
        )
        store.unregister(SEARCH_PLUGIN)

        val tools = toolsFrom(store)

        assertNull(tools.router.getHandler("code_search"))
        assertFalse(tools.grammar.contains("code_search"))
    }

    @Test
    fun givenAChangedSource_whenAListenerIsRegistered_thenEveryMutationNotifiesIt() {
        val store = ToolSourceStore()
        var notifications = 0
        val listener: () -> Unit = { notifications++ }
        store.addChangeListener(listener)

        val source = FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "create_issue")))
        store.register(source)
        store.toolsChanged(MCP)
        store.unregister(MCP)
        store.removeChangeListener(listener)
        store.register(source)

        assertEquals("register, change and unregister each rebuild; a removed listener does not", 3, notifications)
    }

    @Test
    fun givenASourceThatThrowsListingTools_whenHandlersAreBuilt_thenTheOtherSourcesSurvive() {
        // One bad .cgp costs the user its own tools, not the agent.
        val store = ToolSourceStore()
        store.register(FakeToolSource("com.example.broken", listThrows = IllegalStateException("boom")))
        store.register(
            FakeToolSource(SEARCH_PLUGIN, tools = listOf(contributedTool(SEARCH_PLUGIN, "code_search")))
        )

        val handlers = store.handlers(setOf("edit_file"))

        assertEquals(1, handlers.size)
        assertEquals("code_search", handlers.first().toolName)
    }

    @Test
    fun givenAToolNamedLikeABuiltIn_whenHandlersAreBuilt_thenTheBuiltInKeepsTheName() {
        // A provider id with nothing to make an alias from is the only way to reach a bare name.
        val store = ToolSourceStore()
        store.register(FakeToolSource("***", tools = listOf(contributedTool("***", "edit_file"))))

        val tools = toolsFrom(store)

        assertTrue(
            "edit_file must still route to the built-in handler",
            tools.router.getHandler("edit_file") is BuiltInHandler
        )
        assertTrue(tools.contributedHandlers.isEmpty())
    }

    @Test
    fun givenTwoSourcesOfferingTheSameToolName_whenHandlersAreBuilt_thenTheSecondIsQualified() {
        val store = ToolSourceStore()
        store.register(FakeToolSource("com.example.one", tools = listOf(contributedTool("com.example.one", "search"))))
        store.register(FakeToolSource("com.example.one2", tools = listOf(contributedTool("com.example.one2", "search"))))

        val handlers = store.handlers(emptySet())

        assertEquals(listOf("search", "one2_search"), handlers.map { it.toolName })
    }

    @Test
    fun givenABlankProviderId_whenRegistered_thenItIsRefused() {
        val store = ToolSourceStore()

        store.register(FakeToolSource("   ", tools = listOf(contributedTool("   ", "search"))))

        assertTrue(store.sources().isEmpty())
    }

    @Test
    fun givenARegisteredSource_whenReRegisteredUnderTheSameId_thenItReplacesRatherThanDuplicates() {
        // Re-registration is how a provider recovers after this plugin restarts.
        val store = ToolSourceStore()
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "create_issue"))))
        store.register(FakeToolSource(MCP, tools = listOf(contributedTool(MCP, "list_issues"))))

        val handlers = store.handlers(emptySet())

        assertEquals(1, store.sources().size)
        assertEquals(listOf("list_issues"), handlers.map { it.toolName })
    }
}
