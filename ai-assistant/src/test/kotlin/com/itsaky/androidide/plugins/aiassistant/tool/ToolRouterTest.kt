package com.itsaky.androidide.plugins.aiassistant.tool

import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [ToolRouter] dispatch, focused on the difference between a tool that failed
 * and a tool that was cancelled.
 */
class ToolRouterTest {

    private class ThrowingHandler(
        override val toolName: String,
        private val error: Throwable,
    ) : ToolHandler {
        override val description = "throws"
        override suspend fun execute(args: Map<String, Any?>): ToolResult = throw error
    }

    @Test
    fun givenACancelledTool_whenDispatched_thenCancellationPropagatesInsteadOfBecomingAFailure() {
        // It extends Exception, so a broad catch would turn Stop into an ordinary tool failure.
        val router = ToolRouter(listOf(ThrowingHandler("boom", CancellationException("stopped"))))

        try {
            runBlocking { router.dispatch("boom", emptyMap()) }
            fail("dispatch should have rethrown CancellationException")
        } catch (ce: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun givenAFailingTool_whenDispatched_thenTheErrorIsReportedAsAFailureResult() {
        val router = ToolRouter(listOf(ThrowingHandler("boom", IllegalStateException("bad"))))

        val result = runBlocking { router.dispatch("boom", emptyMap()) }

        assertFalse(result.success)
        assertTrue(result.message.contains("bad"))
    }

    @Test
    fun givenAnUnknownTool_whenDispatched_thenItFailsCleanly() {
        val router = ToolRouter(emptyList())

        val result = runBlocking { router.dispatch("nope", emptyMap()) }

        assertFalse(result.success)
        assertTrue(result.message.contains("Unknown tool"))
    }
}
