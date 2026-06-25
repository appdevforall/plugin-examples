package com.example.llama

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.llama.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class ChatRepositoryTest {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    // Mocks for the Repository's dependencies
    private lateinit var mockEngine: LlmInferenceEngine // CHANGED: Mock the engine, not LLamaAndroid
    private lateinit var mockApplication: Application

    // The class under test
    private lateinit var repository: LocalLlmRepositoryImpl

    @Before
    fun setup() {
        // Create fresh mocks before each test
        mockEngine = mock()
        mockApplication = mock()

        // Initialize the repository with the mocked engine
        repository = LocalLlmRepositoryImpl(
            mockApplication,
            mockEngine, // CHANGED: Pass the mocked engine
            ioDispatcher = mainCoroutineRule.testDispatcher
        )
    }

    @Test
    fun `sendMessage with tool use disabled runs simple inference and updates messages`() =
        runTest {
            // Arrange
            val userInput = "Hello, world!"
            val modelResponse = "This is a simple response."
            val modelPath = "/fake/path/to/gemma2.gguf"

            // Arrange: Mock the non-streaming inference call from the engine
            whenever(mockEngine.runInference(any(), any())) doReturn modelResponse
            whenever(mockEngine.loadModel(any())) doReturn 4096 // Needed to set model family

            // Act
            repository.loadModel(modelPath) // Load model to set the correct prompt family
            repository.sendMessage(userInput, isStreaming = false, isToolUseEnabled = false)

            // Assert
            val finalMessages = repository.messages.value
            assertEquals(
                "Should have 2 final messages: user and model response",
                2,
                finalMessages.size
            )
            assertEquals(userInput, finalMessages[0].text)
            assertEquals(modelResponse, finalMessages[1].text)
        }

    @Test
    fun `loadModel success updates messages with system logs`() = runTest {
        // Arrange
        val modelPath = "/fake/path/to/llama-3-model.gguf"
        val expectedContextSize = 4096

        // Arrange: Mock the engine's loadModel to return the context size
        whenever(mockEngine.loadModel(modelPath)).thenReturn(expectedContextSize)

        // Act
        repository.loadModel(modelPath)

        // Assert
        // Check that the engine's loadModel method was called
        verify(mockEngine).loadModel(modelPath)

        // Check that the messages flow was updated with system logs
        val messages = repository.messages.value
        assertTrue(
            "Log should contain model family detection",
            messages.any { it.text.contains("LLAMA3") })
        assertTrue(
            "Log should contain the loaded model name",
            messages.any { it.text.contains(modelPath.substringAfterLast('/')) })
        assertTrue(
            "Log should contain the context size",
            messages.any { it.text.contains("Model context size: $expectedContextSize") })
    }

    @Test
    fun `sendMessage with streaming inference updates final message correctly`() = runTest {
        // Arrange
        val userInput = "Tell me a joke"
        val modelResponseChunks = listOf("Why ", "did the ", "scarecrow ", "win an award?")
        val expectedFullResponse = "Why did the scarecrow win an award?"

        // Arrange: Mock the streaming inference call from the engine
        whenever(
            mockEngine.runStreamingInference(
                any(),
                any()
            )
        ) doReturn modelResponseChunks.asFlow()

        // Act
        repository.sendMessage(userInput, isStreaming = true, isToolUseEnabled = false)

        // Assert
        val finalMessages = repository.messages.value
        assertEquals(2, finalMessages.size)
        assertEquals(expectedFullResponse, finalMessages.last().text)
    }

    @Test
    fun `sendMessage with valid tool call follows two-step agent loop`() = runTest {
        // --- Arrange ---
        val userInput = "What is the time?"
        val modelToolCallResponse =
            """<tool_call>{"name": "get_current_datetime", "args": {}}</tool_call>"""
        val finalAnswer = "The current time is October 10, 2025, 9:38 AM."
        val modelPath = "/fake/path/to/gemma2.gguf"

        val promptCaptor = argumentCaptor<String>()
        val stopStringsCaptor = argumentCaptor<List<String>>()

        // Arrange: Mock the two-step non-streaming inference calls
        whenever(mockEngine.runInference(any(), any()))
            .doReturn(modelToolCallResponse) // First call returns the tool XML
            .doReturn(finalAnswer)           // Second call returns the final text answer

        whenever(mockEngine.loadModel(any())) doReturn 4096

        // --- Act ---
        repository.loadModel(modelPath) // Set model family
        repository.sendMessage(userInput, isStreaming = false, isToolUseEnabled = true)

        // --- Assert ---
        // Verify that the engine's non-streaming inference method was called twice
        verify(mockEngine, times(2)).runInference(
            promptCaptor.capture(),
            stopStringsCaptor.capture()
        )

        // Assert on the first call (tool selection)
        assertTrue(
            "First prompt must contain tool JSON",
            promptCaptor.firstValue.contains(""""name": "get_current_datetime"""")
        )
        assertEquals(
            "First call should stop on </tool_call>",
            listOf("</tool_call>"),
            stopStringsCaptor.firstValue
        )

        // Assert on the second call (final answer)
        assertTrue(
            "Second prompt must contain the tool result 'Information:'",
            promptCaptor.secondValue.contains("Information: The current date and time is")
        )
        assertTrue(
            "Second prompt must contain 'Question:'",
            promptCaptor.secondValue.contains("Question: $userInput")
        )

        val finalMessages = repository.messages.value
        assertEquals("Expected 4 messages after a successful tool call", 4, finalMessages.size)
        assertTrue(finalMessages[1].text.contains("Tool Call: get_current_datetime"))
        assertEquals(Sender.TOOL, finalMessages[2].type)
        assertEquals(finalAnswer, finalMessages[3].text)
    }

    @Test
    fun `sendMessage when model does not select a tool should display direct answer`() = runTest {
        // --- Arrange ---
        val userInput = "Make me a sandwich"
        val modelDirectAnswer = "I am an AI and cannot make a physical sandwich."
        val modelPath = "/fake/path/to/gemma2.gguf"

        // Arrange: Mock a single inference call that returns a direct answer
        whenever(mockEngine.runInference(any(), any())) doReturn modelDirectAnswer
        whenever(mockEngine.loadModel(any())) doReturn 4096

        // --- Act ---
        repository.loadModel(modelPath)
        repository.sendMessage(userInput, isStreaming = false, isToolUseEnabled = true)

        // --- Assert ---
        // Verify that runInference was called only ONCE
        verify(mockEngine, times(1)).runInference(any(), any())

        val finalMessages = repository.messages.value
        assertEquals("Expected 2 messages after a direct answer", 2, finalMessages.size)
        assertEquals(modelDirectAnswer, finalMessages[1].text)
    }
}
