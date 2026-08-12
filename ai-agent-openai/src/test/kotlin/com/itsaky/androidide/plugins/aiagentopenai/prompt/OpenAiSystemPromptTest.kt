package com.itsaky.androidide.plugins.aiagentopenai.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt. The tool-call envelope is what the caller parses back out of the reply, so a
 * paraphrase of it would produce replies nothing reads.
 */
class OpenAiSystemPromptTest {

    private fun request(
        syntax: String? = """<tool_call>{"tool":"NAME","args":{}}</tool_call>""",
        tools: List<ToolDefinition> = listOf(
            ToolDefinition("read_file", "Read a file", emptyMap()),
            ToolDefinition("respond", "Finish the task", emptyMap()),
        ),
        examplePath: String? = "app/src/main/java/com/example/MainActivity.kt",
    ) = SystemPromptRequest(tools, syntax, examplePath)

    @Test
    fun givenAToolCallSyntax_whenBuilt_thenItAppearsVerbatim() {
        val syntax = """@@CALL{"tool":"NAME"}@@"""
        assertTrue(OpenAiSystemPrompt.build(request(syntax = syntax)).contains(syntax))
    }

    @Test
    fun givenTools_whenBuilt_thenEachNameAndDescriptionIsListed() {
        val prompt = OpenAiSystemPrompt.build(request())
        assertTrue(prompt.contains("- read_file: Read a file"))
        assertTrue(prompt.contains("- respond: Finish the task"))
    }

    @Test
    fun givenAnExamplePath_whenBuilt_thenItIsUsedInTheExamples() {
        val prompt = OpenAiSystemPrompt.build(request(examplePath = "src/Foo.kt"))
        assertTrue(prompt.contains(""""file_path":"src/Foo.kt""""))
        // The stem drives the search_project example.
        assertTrue(prompt.contains(""""query":"Foo""""))
    }

    @Test
    fun givenNoTools_whenBuilt_thenThePromptStillBuilds() {
        val prompt = OpenAiSystemPrompt.build(request(tools = emptyList()))
        assertTrue(prompt.contains("AVAILABLE TOOLS:"))
    }

    @Test
    fun givenNoToolCallSyntax_whenBuilt_thenTheEnvelopeSectionIsLeftOut() {
        // A null syntax means the caller parses no envelope; teaching one produces unread replies.
        val prompt = OpenAiSystemPrompt.build(request(syntax = null))

        assertFalse(prompt.contains("TOOL CALL FORMAT"))
        assertFalse(prompt.contains("<tool_call>"))
        assertTrue(prompt.contains("AVAILABLE TOOLS:"))
        assertTrue(prompt.contains("WORKFLOW:"))
    }

    @Test
    fun givenNoExamplePath_whenBuilt_thenTheExamplesStillCarryAConcretePath() {
        val prompt = OpenAiSystemPrompt.build(request(examplePath = null))

        assertTrue(prompt.contains(""""file_path":"app/src/main/java/com/example/MainActivity.kt""""))
    }

    @Test
    fun givenAnyRequest_whenBuilt_thenNativeFunctionCallingIsForbidden() {
        // This backend declares no ToolCallingBackend, so a model using its own channel would hang.
        val prompt = OpenAiSystemPrompt.build(request())
        assertTrue(prompt.contains("native function-calling channel"))
    }

    @Test
    fun givenAnyRequest_whenBuilt_thenOneToolCallPerReplyIsRequired() {
        assertTrue(OpenAiSystemPrompt.build(request()).contains("Emit ONE tool call per reply"))
    }
}
