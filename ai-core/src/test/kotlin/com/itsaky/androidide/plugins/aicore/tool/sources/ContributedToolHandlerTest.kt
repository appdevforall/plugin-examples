package com.itsaky.androidide.plugins.aicore.tool.sources

import com.itsaky.androidide.plugins.aicore.tool.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ContributedToolHandler]: nothing a provider does — throwing, failing its future,
 * never completing — may reach the agent as anything other than a tool result.
 */
class ContributedToolHandlerTest {

    private companion object {
        const val PROVIDER = "com.itsaky.androidide.plugins.aiagentmcp"
    }

    private fun handlerFor(
        source: FakeToolSource,
        tool: ContributedTool,
    ) = ContributedToolHandler(source, tool, "aiagentmcp_${tool.name}")

    @Test
    fun givenAFutureCompletingExceptionally_whenTheToolRuns_thenItBecomesAFailureResult() {
        val source = FakeToolSource(PROVIDER, displayName = "MCP")
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue"))

        val result = runBlocking {
            val running = async(Dispatchers.Default) { handler.execute(emptyMap()) }
            withTimeout(5_000) {
                while (source.calls.isEmpty()) delay(5)
                source.calls.values.first().completeExceptionally(IllegalStateException("upstream 503"))
                running.await()
            }
        }

        assertFalse(result.success)
        assertTrue("the reason must reach the model: ${result.message}", result.message.contains("upstream 503"))
    }

    @Test
    fun givenASourceThatThrowsOnInvoke_whenTheToolRuns_thenItBecomesAFailureResult() {
        val source = FakeToolSource(PROVIDER, displayName = "MCP")
        source.invokeThrows = LinkageError("provider was uninstalled mid-run")
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue"))

        val result = runBlocking { handler.execute(emptyMap()) }

        assertFalse(result.success)
        assertTrue(result.message.contains("MCP"))
    }

    @Test
    fun givenASuccessfulOutcome_whenTheToolRuns_thenTheOutputIsCarriedBack() {
        val source = FakeToolSource(PROVIDER, displayName = "MCP")
        source.immediateOutcome = ContributedToolResult(success = true, output = "issue #12 created")
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue"))

        val result = runBlocking { handler.execute(emptyMap()) }

        assertTrue(result.success)
        assertEquals("issue #12 created", result.data)
    }

    @Test
    fun givenAFailedOutcome_whenTheToolRuns_thenTheProvidersSentenceIsReported() {
        val source = FakeToolSource(PROVIDER, displayName = "MCP")
        source.immediateOutcome = ContributedToolResult(
            success = false,
            output = "",
            errorMessage = "The server rejected the token.",
        )
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue"))

        val result = runBlocking { handler.execute(emptyMap()) }

        assertFalse(result.success)
        assertEquals("The server rejected the token.", result.message)
    }

    @Test
    fun givenAMissingRequiredArgument_whenValidated_thenItIsRejectedBeforeAnyDialog() {
        // validate() runs before the approval prompt, so a doomed call costs no user attention.
        val source = FakeToolSource(PROVIDER)
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue", required = listOf("title")))

        val validation = runBlocking { handler.validate(mapOf("body" to "text")) }

        assertTrue(validation is Validation.Rejected)
        assertTrue((validation as Validation.Rejected).result.message.contains("title"))
    }

    @Test
    fun givenEveryRequiredArgument_whenValidated_thenTheCallIsAccepted() {
        val source = FakeToolSource(PROVIDER)
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue", required = listOf("title")))

        val validation = runBlocking { handler.validate(mapOf("title" to "Crash on launch")) }

        assertTrue(validation is Validation.Accepted)
    }

    @Test
    fun givenAnInFlightCall_whenTheRunIsCancelled_thenTheProviderIsToldToCancel() {
        val source = FakeToolSource(PROVIDER)
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue"))

        runBlocking {
            val running = async(Dispatchers.Default) { handler.execute(emptyMap()) }
            withTimeout(5_000) {
                while (source.calls.isEmpty()) delay(5)
                running.cancel()
                while (source.cancelled.isEmpty()) delay(5)
            }
        }

        assertEquals(source.calls.keys.toList(), source.cancelled)
    }

    @Test
    fun givenAContributedTool_whenItIsBuilt_thenItIsNeverSessionApprovable() {
        val source = FakeToolSource(PROVIDER)
        val handler = handlerFor(source, contributedTool(PROVIDER, "create_issue"))

        assertFalse(handler.allowsSessionApproval)
        assertTrue("contributed tools default to asking every time", handler.requiresApproval)
    }

    @Test
    fun givenAToolDeclaringNoApproval_whenItIsBuilt_thenTheDeclarationIsHonoured() {
        val source = FakeToolSource("com.example.searchplugin")
        val tool = contributedTool(
            "com.example.searchplugin",
            "code_search",
            requiresApproval = false,
            readOnly = true,
        )

        val handler = ContributedToolHandler(source, tool, "searchplugin_code_search")

        assertFalse(handler.requiresApproval)
        assertTrue(handler.readOnly)
    }
}
