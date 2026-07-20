package com.itsaky.androidide.plugins.aiassistant.tool

import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.PathGuard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for the [Executor] path-containment pre-guard, in particular the
 * [ToolHandler.resolvesPathsInternally] opt-out that lets read-only handlers
 * rescue odd paths (e.g. a model-supplied "/.gitignore") instead of the Executor
 * rejecting them outright, while write tools stay guarded.
 */
class ExecutorTest {

    private lateinit var projectRoot: File

    /** A handler that records whether it was dispatched and always succeeds. */
    private class FakeHandler(
        override val toolName: String,
        override val resolvesPathsInternally: Boolean,
    ) : ToolHandler {
        override val description = "fake"
        override val requiresApproval = false
        override val pathArgs = listOf("file_path")
        var dispatched = false
            private set

        override suspend fun execute(args: Map<String, Any?>): ToolResult {
            dispatched = true
            return ToolResult.success("ran")
        }
    }

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("executor-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
    }

    private fun executorFor(handler: ToolHandler): Executor =
        Executor(ToolRouter(listOf(handler)), ToolApprovalManager())

    @Test
    fun givenAnInternallyResolvingHandler_whenExecutingAnEscapingPath_thenTheEscapePreGuardIsBypassed() = runBlocking {
        val handler = FakeHandler("fake_internal", resolvesPathsInternally = true)
        val executor = executorFor(handler)

        // "/escape.txt" resolves outside the project root; the guard would reject
        // it, but an internally-resolving handler must still be dispatched.
        val results = executor.execute(listOf(ToolCall("fake_internal", mapOf("file_path" to "/escape.txt"))))

        assertTrue("handler should have been dispatched", handler.dispatched)
        assertTrue("result should be the handler's success", results.single().success)
    }

    @Test
    fun givenADefaultHandler_whenExecutingAPathThatEscapesTheProjectRoot_thenItIsRejected() = runBlocking {
        val handler = FakeHandler("fake_guarded", resolvesPathsInternally = false)
        val executor = executorFor(handler)

        val results = executor.execute(listOf(ToolCall("fake_guarded", mapOf("file_path" to "/escape.txt"))))

        assertFalse("handler must NOT run for an escaping write path", handler.dispatched)
        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("outside the project directory"))
    }

    @Test
    fun givenADefaultHandler_whenExecutingAnInProjectPath_thenItRuns() = runBlocking {
        val handler = FakeHandler("fake_guarded", resolvesPathsInternally = false)
        val executor = executorFor(handler)

        val results = executor.execute(listOf(ToolCall("fake_guarded", mapOf("file_path" to "notes.txt"))))

        assertTrue("in-project path should be allowed through", handler.dispatched)
        assertTrue(results.single().success)
    }
}
