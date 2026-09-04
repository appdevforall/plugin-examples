package com.itsaky.androidide.plugins.aiagentlocal.settings

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.model.DeviceMemory
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelFileInfo
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelFileSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The persisted read grant is the only thing keeping a model readable now that nothing is copied,
 * so releasing the wrong one strands a model the user is still running. These pin the grant
 * lifecycle across the paths that abandon a selection. See ADFA-5253.
 */
class LocalLlmSettingsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    /** Records what was granted and given back, and answers as the file source would. */
    private class FakeModelFiles : ModelFileSource {
        val persisted = mutableListOf<String>()
        val released = mutableListOf<String>()

        /** References the provider will not serve, standing in for a deleted document. */
        val unreadable = mutableSetOf<String>()

        /** Makes the lookup blow up, standing in for a provider that fails mid-selection. */
        var failInfo = false

        override fun info(context: Context, uriString: String): ModelFileInfo {
            if (failInfo) throw IllegalStateException("provider failed")
            return ModelFileInfo(fallbackDisplayName(uriString), 1_024L)
        }

        override fun openStream(context: Context, uriString: String): InputStream? = null

        override fun isReadable(context: Context, uriString: String) = uriString !in unreadable

        override fun fallbackDisplayName(uriOrPath: String) = uriOrPath.substringAfterLast('/')

        override fun persistAccess(context: Context, uriString: String): Boolean {
            persisted += uriString
            return true
        }

        override fun releaseAccess(context: Context, uriString: String) {
            released += uriString
        }
    }

    private lateinit var stored: MutableMap<String, String?>
    private lateinit var resolver: ContentResolver
    private lateinit var pluginContext: PluginContext
    private lateinit var modelFiles: FakeModelFiles

    @Before
    fun setup() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { Uri.decode(any()) } answers { firstArg() }

        stored = mutableMapOf()
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getString(any(), any()) } answers { stored[firstArg()] ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            editor
        }

        resolver = mockk(relaxed = true)
        // The GGUF sniff fails OPEN, so a pick is accepted unless a test serves other bytes.
        every { resolver.openInputStream(any()) } returns null
        val androidContext = mockk<Context>(relaxed = true)
        every { androidContext.contentResolver } returns resolver

        pluginContext = mockk(relaxed = true)
        every { pluginContext.androidContext } returns androidContext
        every { pluginContext.getPluginSharedPreferences(any()) } returns prefs

        modelFiles = FakeModelFiles()
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    /**
     * Unconfined, so every launch runs inline: nothing here suspends on a real dispatcher, and the
     * memory pre-flight fails open on the fake's unreadable header.
     */
    private fun viewModel(deviceMemory: DeviceMemory = DeviceMemory { null }) =
        LocalLlmSettingsViewModel(
            getContext = { pluginContext },
            ioDispatcher = Dispatchers.Unconfined,
            deviceMemory = deviceMemory,
            modelFiles = modelFiles,
        )

    @Test
    fun givenASelection_whenItIsKept_thenItsGrantIsPersistedAndStored() {
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(listOf(MODEL_A), modelFiles.persisted)
        assertEquals(emptyList<String>(), modelFiles.released)
        assertEquals(MODEL_A, viewModel.getLocalModelPath())
        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
    }

    @Test
    fun givenAConfiguredModel_whenAnotherIsSelected_thenOnlyTheReplacedGrantIsReleased() {
        // Grants are capped per app, so the model no longer read by anything has to give its back.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_A, MODEL_B), modelFiles.persisted)
        assertEquals(listOf(MODEL_A), modelFiles.released)
        assertEquals(MODEL_B, viewModel.getLocalModelPath())
    }

    @Test
    fun givenAConfiguredModel_whenItIsReSelected_thenItsGrantIsNotReleased() {
        // "Load from saved" re-picks the configured model; releasing here would revoke the grant
        // on the model the user is still running.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(emptyList<String>(), modelFiles.released)
        assertEquals(MODEL_A, viewModel.getLocalModelPath())
    }

    @Test
    fun givenAConfiguredModelThatIsGone_whenItIsReSelected_thenItsGrantSurvivesTheFailure() {
        // The model may be on storage that is merely unmounted; re-mounting must not need a pick.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A

        viewModel.loadModelFromUri(MODEL_A)

        assertEquals(emptyList<String>(), modelFiles.released)
        assertEquals(ModelLoadingState.Unavailable("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)
    }

    @Test
    fun givenANewSelectionThatIsRejected_thenItsOwnGrantIsGivenBackAndTheConfiguredOneKept() {
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_B

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals("the configured model must survive a failed pick", MODEL_A, viewModel.getLocalModelPath())
    }

    @Test
    fun givenANonGgufSelection_thenItIsRejectedWithoutBeingStoredAndItsGrantIsReleased() {
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals(null, viewModel.getLocalModelPath())
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
    }

    @Test
    fun givenARejectedSelection_thenTheConfiguredModelsReadinessIsLeftAlone() {
        // The pane keys its "(unavailable)" marker off the engine status, so a rejected pick of
        // another file must leave it alone.
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        val viewModel = viewModel()
        stored[KEY_MODEL_PATH] = MODEL_A
        viewModel.refreshSavedModelAvailability()
        modelFiles.unreadable += MODEL_A
        viewModel.refreshSavedModelAvailability()
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)

        viewModel.loadModelFromUri(MODEL_B)

        // Not Initializing: the pick published that on its way in and never got anywhere.
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
    }

    @Test
    fun givenAConfiguredModelThatWentAway_whenTheScreenReturns_thenItIsReportedUnavailable() {
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A

        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Unavailable("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.ModelUnavailable, viewModel.state.value?.engine)
        assertEquals("a re-check must not touch the grant", emptyList<String>(), modelFiles.released)
    }

    @Test
    fun givenAModelThatCameBack_whenTheScreenReturns_thenItIsReportedReadyAgain() {
        // Unmounted storage comes back; the stale "unavailable" has to clear without a fresh pick.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        modelFiles.unreadable += MODEL_A
        viewModel.refreshSavedModelAvailability()

        modelFiles.unreadable -= MODEL_A
        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenARejectedPicksError_whenTheScreenReturnsAndTheModelReadsBack_thenItClears() {
        // The error described the pick; left standing it shows on every return to the screen.
        val viewModel = viewModel()
        viewModel.loadModelFromUri(MODEL_A)
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        viewModel.loadModelFromUri(MODEL_B)
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)

        viewModel.refreshSavedModelAvailability()

        assertEquals(ModelLoadingState.Loaded("a.gguf"), viewModel.state.value?.model)
        assertEquals(EngineState.Initialized, viewModel.state.value?.engine)
    }

    @Test
    fun givenAPickAbandonedBeforeItWasStored_thenItsGrantIsGivenBackByTheFinally() {
        // Grants are capped, and only the finally covers every way out of the selection.
        val viewModel = viewModel()

        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream("NOPE".toByteArray()) }
        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.persisted)
        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals(null, viewModel.getLocalModelPath())
    }

    @Test
    fun givenASelectionThatThrows_thenItsGrantIsStillGivenBack() {
        // Leaves through code no abandon path runs, as a cancellation at the dialog would.
        modelFiles.failInfo = true
        val viewModel = viewModel()

        viewModel.loadModelFromUri(MODEL_B)

        assertEquals(listOf(MODEL_B), modelFiles.persisted)
        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertTrue(viewModel.state.value?.model is ModelLoadingState.Error)
    }

    @Test
    fun givenAModelDeclinedAtTheMemoryWarning_thenItsGrantIsGivenBackAndNothingIsStored() {
        val viewModel = viewModel(deviceMemory = DeviceMemory { 1L })
        viewModel.loadModelFromUri(MODEL_B)
        assertTrue("the pre-flight must be waiting on an answer", viewModel.hasPendingMemoryWarning)

        viewModel.onMemoryWarningDecision(false)

        assertEquals(listOf(MODEL_B), modelFiles.released)
        assertEquals(null, viewModel.getLocalModelPath())
    }

    @Test
    fun givenADeclineAtTheMemoryWarning_whenSomethingWasStoredMeanwhile_thenItIsNotReverted() {
        // The decline owns the two status lines and nothing else in the state.
        val viewModel = viewModel(deviceMemory = DeviceMemory { 1L })
        viewModel.loadModelFromUri(MODEL_B)
        viewModel.saveLocalModelPath(MODEL_A)

        viewModel.onMemoryWarningDecision(false)

        assertEquals(MODEL_A, viewModel.state.value?.savedModelPath)
    }

    private companion object {
        const val MODEL_A = "content://com.android.externalstorage.documents/document/a.gguf"
        const val MODEL_B = "content://com.android.externalstorage.documents/document/b.gguf"
        const val KEY_MODEL_PATH = "local_llm_model_path"
    }
}
