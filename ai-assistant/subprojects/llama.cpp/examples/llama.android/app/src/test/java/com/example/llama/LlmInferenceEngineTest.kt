package com.example.llama

import android.llama.cpp.LLamaAndroid
import com.example.llama.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class LlmInferenceEngineTest {

    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    // The dependency to be mocked
    private lateinit var mockLlama: LLamaAndroid

    // The class under test
    private lateinit var engine: LlmInferenceEngine

    private val testModelPath = "/data/model.gguf"

    @Before
    fun setup() {
        mockLlama = mock()
        engine = LlmInferenceEngine(
            llama = mockLlama,
            ioDispatcher = mainCoroutineRule.testDispatcher
        )
    }

    // A helper function to reduce boilerplate in tests that need a model loaded.
    private suspend fun loadTestModel() {
        whenever(mockLlama.getContextSize()).thenReturn(4096)
        engine.loadModel(testModelPath)
    }

    @Test
    fun `loadModel when no model is loaded loads successfully and updates state`() = runTest {
        // Arrange
        val expectedContextSize = 4096
        whenever(mockLlama.getContextSize()).thenReturn(expectedContextSize)

        // Act
        val actualContextSize = engine.loadModel(testModelPath)

        // Assert
        verify(mockLlama).load(testModelPath)
        assertEquals(expectedContextSize, actualContextSize)
        assertTrue("Engine should be marked as loaded", engine.isModelLoaded)
        assertEquals(testModelPath, engine.loadedModelPath)
    }

    @Test
    fun `loadModel when same model is already loaded does nothing`() = runTest {
        // Arrange
        loadTestModel() // Load the model once

        // Act
        val contextSize = engine.loadModel(testModelPath) // Attempt to load the same model again

        // Assert
        verify(mockLlama, times(1)).load(testModelPath)
        assertEquals(4096, contextSize)
    }

    @Test
    fun `loadModel when a different model is loaded unloads old one first`() = runTest {
        // Arrange
        val newModelPath = "/data/new_model.gguf"
        loadTestModel() // Load the initial model

        // Act
        engine.loadModel(newModelPath)

        // Assert
        verify(mockLlama).unload() // Verify the old model was unloaded
        verify(mockLlama).load(newModelPath) // Verify the new one was loaded
        assertEquals(newModelPath, engine.loadedModelPath)
    }

    @Test
    fun `unloadModel when model is loaded unloads and resets state`() = runTest {
        // Arrange
        loadTestModel()
        assertTrue(engine.isModelLoaded) // Pre-condition check

        // Act
        engine.unloadModel()

        // Assert
        verify(mockLlama).unload()
        assertFalse("Engine should not be marked as loaded", engine.isModelLoaded)
        assertNull("Loaded model path should be null", engine.loadedModelPath)
    }

    @Test
    fun `unloadModel when no model is loaded does nothing`() = runTest {
        // Arrange
        assertFalse(engine.isModelLoaded) // Pre-condition check

        // Act
        engine.unloadModel()

        // Assert
        verify(mockLlama, never()).unload()
    }


//    @Test
//    fun `runInference when model is not loaded throws IllegalStateException`() = runTest {
//        // Act & Assert
//        assertThrows<IllegalStateException> {
//            engine.runInference("prompt")
//        }
//    }

    @Test
    fun `runInference when model is loaded returns concatenated string`() = runTest {
        // Arrange
        loadTestModel()
        val prompt = "Hello"
        val responseFlow = flowOf("The ", "final ", "response.")
        val expectedResponse = "The final response."
        whenever(mockLlama.send(any(), any(), any(), any())).thenReturn(responseFlow)

        // Act
        val actualResponse = engine.runInference(prompt)

        // Assert
        verify(mockLlama).clearKvCache()
        verify(mockLlama).send(prompt, stop = emptyList())
        assertEquals(expectedResponse, actualResponse)
    }

//    @Test
//    fun `runStreamingInference when model is not loaded throws IllegalStateException`() {
//        // Act & Assert
//        assertThrows<IllegalStateException> {
//            engine.runStreamingInference("prompt")
//        }
//    }

    @Test
    fun `runStreamingInference when model is loaded returns flow of strings`() = runTest {
        // Arrange
        loadTestModel()
        val prompt = "Stream hello"
        val expectedChunks = listOf("The ", "streaming ", "response.")
        val responseFlow = flowOf(*expectedChunks.toTypedArray())
        whenever(mockLlama.send(any(), any())).thenReturn(responseFlow)

        // Act
        val resultFlow = engine.runStreamingInference(prompt)
        val actualChunks = resultFlow.toList()

        // Assert
        verify(mockLlama).clearKvCache()
        verify(mockLlama).send(prompt, stop = emptyList())
        assertEquals(expectedChunks, actualChunks)
    }

    @Test
    fun `getContextSize returns size when loaded and 0 when not loaded`() = runTest {
        // Assert initial state
        assertEquals("Should return 0 when no model is loaded", 0, engine.getContextSize())

        // Arrange & Act
        loadTestModel()

        // Assert loaded state
        assertEquals(
            "Should return context size when model is loaded",
            4096,
            engine.getContextSize()
        )
    }

//    @Test
//    fun `bench when model is not loaded throws IllegalStateException`() = runTest {
//        // Act & Assert
//        assertThrows<IllegalStateException> {
//            engine.bench(512, 128, 1, 1)
//        }
//    }

    @Test
    fun `bench when model is loaded calls native method`() = runTest {
        // Arrange
        loadTestModel()
        val expectedResult = "Benchmark complete."
        whenever(mockLlama.bench(512, 128, 1, 1)).thenReturn(expectedResult)

        // Act
        val actualResult = engine.bench(512, 128, 1, 1)

        // Assert
        verify(mockLlama).bench(512, 128, 1, 1)
        assertEquals(expectedResult, actualResult)
    }
}
