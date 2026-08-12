package com.itsaky.androidide.plugins.aiassistant.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.itsaky.androidide.plugins.aiassistant.gemini.GeminiCatalogGateway
import com.itsaky.androidide.plugins.aiassistant.memory.DeviceMemory
import com.itsaky.androidide.plugins.aiassistant.memory.ModelMemoryGate
import com.itsaky.androidide.plugins.aiassistant.util.GgufWriter
import com.itsaky.androidide.plugins.aiassistant.util.ModelFileInfo
import com.itsaky.androidide.plugins.aiassistant.util.ModelFileSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

/**
 * Tests the memory pre-flight end to end through the ViewModel: what the user is asked and what is
 * persisted — persisting the path is what makes ai-core load, so "not persisted" asserts no load.
 * Free RAM and the file lookup are faked; the model is a real file with a real GGUF header.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelMemoryTest {

    /** The ViewModel touches LiveData in its init block and posts to it from the load. */
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val megabyte = 1024L * 1024
    private val gigabyte = 1024 * megabyte

    /**
     * gemma-3-1b's shape: a 122,683,392-byte KV cache at full context, plus the 256 MB compute
     * buffer. A literal, so this asserts the arithmetic rather than restating it.
     */
    private val expectedRunBytes = 122_683_392L + 256 * megabyte

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var modelFile: File
    private lateinit var fileSource: FakeModelFileSource

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        modelFile = tempModel("model.gguf", gguf())
        fileSource = FakeModelFileSource()
        // Only for GgufFileInspector's pre-check; everything else goes through the file source.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun givenPlentyOfFreeMemory_whenAModelIsSelected_thenItIsAcceptedWithoutAWarning() = runTest {
        val viewModel = viewModel(availableBytes = 8 * gigabyte)
        val asked = collectWarnings(viewModel)

        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        assertTrue(asked.isEmpty())
        assertEquals(modelFile.absolutePath, viewModel.savedModelPath.value)
        assertTrue(viewModel.modelLoadingState.value is ModelLoadingState.Loaded)
    }

    @Test
    fun givenTooLittleFreeMemory_whenAModelIsSelected_thenTheUserIsAskedWithTheFigures() = runTest {
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        val asked = collectWarnings(viewModel)

        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        assertEquals(1, asked.size)
        val warning = asked.single()
        assertEquals(modelFile.name, warning.modelName)
        assertEquals(modelFile.length(), warning.loadBytes)
        assertEquals(expectedRunBytes, warning.runBytes)
        assertEquals(64 * megabyte, warning.availableBytes)
        assertEquals(ModelMemoryGate.Severity.INSUFFICIENT, warning.severity)
    }

    @Test
    fun givenTheMemoryWarning_whenTheUserCancels_thenTheModelIsNeverPersisted() = runTest {
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        collectWarnings(viewModel)
        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        viewModel.onMemoryWarningDecision(proceed = false)
        runCurrent()

        assertNull(viewModel.savedModelPath.value)
        assertEquals(ModelLoadingState.Idle, viewModel.modelLoadingState.value)
    }

    @Test
    fun givenTheMemoryWarning_whenTheUserCancels_thenTheDocumentGrantIsGivenBack() = runTest {
        // The grant is taken before the check runs, and its table has a hard per-app limit.
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        collectWarnings(viewModel)
        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        viewModel.onMemoryWarningDecision(proceed = false)
        runCurrent()

        assertEquals(listOf(modelFile.absolutePath), fileSource.released)
    }

    @Test
    fun givenTheMemoryWarning_whenTheUserProceeds_thenTheGrantIsKept() = runTest {
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        collectWarnings(viewModel)
        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        viewModel.onMemoryWarningDecision(proceed = true)
        runCurrent()

        assertEquals(emptyList<String>(), fileSource.released)
    }

    @Test
    fun givenTheMemoryWarning_whenTheUserProceeds_thenTheModelIsPersistedAnyway() = runTest {
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        collectWarnings(viewModel)
        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        viewModel.onMemoryWarningDecision(proceed = true)
        runCurrent()

        assertEquals(modelFile.absolutePath, viewModel.savedModelPath.value)
        assertTrue(viewModel.modelLoadingState.value is ModelLoadingState.Loaded)
    }

    @Test
    fun givenUnreadableFreeMemory_whenAModelIsSelected_thenItIsAcceptedRatherThanQuestioned() = runTest {
        // Failing open: a device we cannot measure must not be told its model won't fit.
        val viewModel = viewModel(availableBytes = null)
        val asked = collectWarnings(viewModel)

        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        assertTrue(asked.isEmpty())
        assertEquals(modelFile.absolutePath, viewModel.savedModelPath.value)
    }

    @Test
    fun givenAnUnknownFileSize_whenAModelIsSelected_thenItIsAcceptedRatherThanQuestioned() = runTest {
        fileSource.sizeOverride = null
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        val asked = collectWarnings(viewModel)

        viewModel.loadModelFromUri(modelFile.absolutePath, androidContext(modelFile))
        runCurrent()

        assertTrue(asked.isEmpty())
        assertEquals(modelFile.absolutePath, viewModel.savedModelPath.value)
    }

    @Test
    fun givenAModelWithoutAReadableHeader_whenSelected_thenTheSizeBasedEstimateIsUsed() = runTest {
        // Valid magic, nothing usable behind it: the estimate falls back instead of vanishing.
        val headerless = tempModel("headerless.gguf", "GGUF".toByteArray() + ByteArray(8))
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        val asked = collectWarnings(viewModel)

        viewModel.loadModelFromUri(headerless.absolutePath, androidContext(headerless))
        runCurrent()

        assertEquals(256 * megabyte, asked.single().runBytes)
    }

    @Test
    fun givenAFileThatIsNotAModel_whenSelected_thenItIsRejectedBeforeTheMemoryCheck() = runTest {
        val notAModel = tempModel("notes.txt", "nowhere near a model file".toByteArray())
        val viewModel = viewModel(availableBytes = 64 * megabyte)
        val asked = collectWarnings(viewModel)

        viewModel.loadModelFromUri(notAModel.absolutePath, androidContext(notAModel))
        runCurrent()

        assertTrue(asked.isEmpty())
        assertNull(viewModel.savedModelPath.value)
        assertTrue(viewModel.modelLoadingState.value is ModelLoadingState.Error)
    }

    private fun viewModel(availableBytes: Long?) = AiSettingsViewModel(
        getContext = { null },
        ioDispatcher = dispatcher,
        catalogGateway = mockk<GeminiCatalogGateway>(relaxed = true),
        deviceMemory = DeviceMemory { availableBytes },
        modelFiles = fileSource,
    )

    /** Collects the warnings the ViewModel raises, so tests can assert on what the user is shown. */
    private fun kotlinx.coroutines.test.TestScope.collectWarnings(
        viewModel: AiSettingsViewModel
    ): List<ModelMemoryWarning> {
        val asked = mutableListOf<ModelMemoryWarning>()
        backgroundScope.launch { viewModel.modelMemoryWarnings.collect { asked += it } }
        return asked
    }

    /**
     * Serves the model file over a mocked resolver, which is all GgufFileInspector's magic-byte
     * pre-check needs. Name, size and the header read go through [fileSource] instead.
     */
    private fun androidContext(file: File): Context {
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(any()) } answers { file.inputStream() }
        return mockk<Context>(relaxed = true) {
            every { contentResolver } returns resolver
        }
    }

    private fun tempModel(name: String, bytes: ByteArray): File =
        tempFolder.newFile(name).apply { writeBytes(bytes) }

    private fun gguf(): ByteArray = GgufWriter()
        .string("general.architecture", "gemma3")
        .uint32("gemma3.block_count", 26)
        .uint32("gemma3.embedding_length", 1152)
        .uint32("gemma3.attention.head_count", 4)
        .uint32("gemma3.attention.head_count_kv", 1)
        .build()

    /**
     * Reads the real files these tests write, with no Android framework on the path — which is what
     * the ViewModel taking a [ModelFileSource] instead of a ContentResolver buys.
     */
    private class FakeModelFileSource : ModelFileSource {

        /** Set to null to simulate a provider that will not report a size. */
        var sizeOverride: Long? = UNSET

        val released = mutableListOf<String>()

        override fun info(context: Context, uriString: String): ModelFileInfo {
            val file = File(uriString)
            val size = if (sizeOverride == UNSET) file.length().takeIf { it > 0L } else sizeOverride
            return ModelFileInfo(file.name, size)
        }

        override fun openStream(context: Context, uriString: String): InputStream? =
            File(uriString).takeIf { it.isFile }?.inputStream()

        override fun fallbackDisplayName(uriOrPath: String): String =
            uriOrPath.substringAfterLast('/')

        override fun releaseAccess(context: Context, uriString: String) {
            released += uriString
        }

        private companion object {
            /** Distinguishes "use the real file length" from a deliberate null. */
            const val UNSET = Long.MIN_VALUE
        }
    }
}
