package com.example.llama

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.llama.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class MainViewModelTest {

    // Junit rule for LiveData
    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    // Junit rule for managing Coroutine Dispatchers
    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    // Mocks for the ViewModel's dependencies
    private lateinit var mockLocalLlmRepositoryImpl: LocalLlmRepositoryImpl

    // The class under test
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        // Create mock before each test
        mockLocalLlmRepositoryImpl = mock()

        // Common setup for all tests: The ViewModel accesses .messages on init, so we must stub it.
        whenever(mockLocalLlmRepositoryImpl.messages).thenReturn(MutableStateFlow(emptyList()))
    }

    @Test
    fun `uiMessages correctly exposes the state from repository`() = runTest {
        // Arrange: Create a test flow that the mock repository will return.
        val testMessages = listOf(ChatMessage(1, "Test Message", Sender.AGENT))
        val messagesFlow = MutableStateFlow(testMessages)
        whenever(mockLocalLlmRepositoryImpl.messages).thenReturn(messagesFlow)

        // Act: Initialize the ViewModel.
        viewModel = MainViewModel(mockLocalLlmRepositoryImpl)

        // Assert: The ViewModel's stateFlow should reflect the repository's flow.
        // FIX: Don't use .value on a StateFlow with WhileSubscribed in tests.
        // There are no subscribers, so it will return the `initialValue`.
        // Instead, use .first() to suspend and collect the first emitted value.
        assertEquals(testMessages, viewModel.chatMessages.first())

        // Arrange for update: Push a new list to our test flow.
        val newTestMessages = listOf(
            ChatMessage(1, "Test Message", Sender.AGENT),
            ChatMessage(2, "User Input", Sender.USER)
        )
        messagesFlow.value = newTestMessages

        // Assert after update: The ViewModel's flow should update accordingly.
        assertEquals(newTestMessages, viewModel.chatMessages.first())
    }

    @Test
    fun `send correctly delegates the call to the repository`() = runTest {
        // Arrange
        val userInput = "What's the time now?"
        val isStreaming = true
        val useTools = true
        viewModel = MainViewModel(mockLocalLlmRepositoryImpl)

        // FIX: Stub the suspend fun on the mock. If you don't, it suspends forever
        // and the `verify` call is never reached. Since it returns Unit, we can
        // use thenReturn(Unit).
        whenever(mockLocalLlmRepositoryImpl.sendMessage(any(), any(), any())).thenReturn(Unit)

        viewModel.message = userInput // Directly set the public property
        viewModel.setStreaming(isStreaming)
        viewModel.setToolUse(useTools)

        // Act
        viewModel.send()

        // Assert
        verify(mockLocalLlmRepositoryImpl).sendMessage(userInput, isStreaming, useTools)
    }

    @Test
    fun `send does not delegate if message is blank`() = runTest {
        // Arrange
        viewModel = MainViewModel(mockLocalLlmRepositoryImpl)
        viewModel.message = "   " // Blank message

        // Act
        viewModel.send()

        // Assert: Verify that sendMessage was NEVER called.
        verify(mockLocalLlmRepositoryImpl, never()).sendMessage(any(), any(), any())
    }


    @Test
    fun `clear correctly delegates the call to the repository`() = runTest {
        // Arrange
        viewModel = MainViewModel(mockLocalLlmRepositoryImpl)

        // Act
        viewModel.clear()

        // Assert
        verify(mockLocalLlmRepositoryImpl).clear()
    }

}
