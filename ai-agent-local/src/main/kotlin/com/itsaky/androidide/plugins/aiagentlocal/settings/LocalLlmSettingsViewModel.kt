package com.itsaky.androidide.plugins.aiagentlocal.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.aiagentlocal.format.ByteSize
import com.itsaky.androidide.plugins.aiagentlocal.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentlocal.model.ContentModelFileSource
import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy
import com.itsaky.androidide.plugins.aiagentlocal.model.DeviceMemory
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufFileInspector
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufHeaderReader
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelFileInfo
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelFileSource
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelMemoryEstimator
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelMemoryGate
import com.itsaky.androidide.plugins.aiagentlocal.model.SystemDeviceMemory
import com.itsaky.androidide.plugins.aiagentlocal.preferences.LocalLlmPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * State for the model file loading.
 */
sealed class ModelLoadingState {
    object Idle : ModelLoadingState()
    object Loading : ModelLoadingState()
    data class Loaded(val modelName: String) : ModelLoadingState()
    data class Error(val message: String) : ModelLoadingState()
}

/**
 * State for the inference engine initialization.
 */
sealed class EngineState {
    object Uninitialized : EngineState()
    object Initializing : EngineState()
    object Initialized : EngineState()
    data class Error(val message: String) : EngineState()
}

/**
 * A selected model that may not fit in this device's memory, with the figures to show the user.
 *
 * @param modelName the model's display name
 * @param loadBytes memory the weights need
 * @param runBytes memory the KV cache and compute buffers need on top of the weights
 * @param availableBytes free RAM when the check ran
 * @param severity whether the shortfall makes failure likely or merely possible
 */
data class ModelMemoryWarning(
    val modelName: String,
    val loadBytes: Long,
    val runBytes: Long,
    val availableBytes: Long,
    val severity: ModelMemoryGate.Severity,
)

/**
 * Backs this backend's own settings pane. Owns everything local-model-specific — the GGUF check,
 * the memory pre-flight, the engine and model status — which is why it lives in this plugin rather
 * than in whichever screen happens to show the pane.
 *
 * @param deviceMemory free-RAM reading for the pre-flight; null builds the live one, which cannot
 *   be a default argument because it needs [logger], and a default cannot reach an instance member
 * @param modelFiles reads a selected model's name, size and bytes; null builds the live one
 */
class LocalLlmSettingsViewModel(
    private val getContext: () -> PluginContext?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    deviceMemory: DeviceMemory? = null,
    modelFiles: ModelFileSource? = null,
) : ViewModel() {

    private val deviceMemory: DeviceMemory = deviceMemory ?: SystemDeviceMemory(
        contextProvider = { getContext()?.androidContext },
        onReadError = { e -> logger?.warn("$TAG: could not read free memory", e) },
    )

    private val modelFiles: ModelFileSource = modelFiles ?: ContentModelFileSource { what, e ->
        logger?.warn("$TAG: $what", e)
    }

    companion object {
        private const val TAG = "$LOG_PREFIX.LocalLlmSettingsViewModel"

        private val KEY_MODEL_PATH = LocalLlmPreferences.KEY_MODEL_PATH
        private val KEY_MODEL_NAME = LocalLlmPreferences.KEY_MODEL_NAME
        private val KEY_MODEL_SHA256 = LocalLlmPreferences.KEY_MODEL_SHA256
        private val KEY_SIMPLE_PROMPT = LocalLlmPreferences.KEY_SIMPLE_PROMPT
    }

    private val _savedModelPath = MutableLiveData<String?>(null)
    val savedModelPath: LiveData<String?> get() = _savedModelPath

    private val _modelLoadingState = MutableLiveData<ModelLoadingState>(ModelLoadingState.Idle)
    val modelLoadingState: LiveData<ModelLoadingState> get() = _modelLoadingState

    private val _engineState = MutableLiveData<EngineState>(EngineState.Initialized)
    val engineState: LiveData<EngineState> get() = _engineState

    /** The memory pre-flight's consent gate; see [loadModelFromUri]. */
    private val memoryConfirmation = UserConfirmation<ModelMemoryWarning>()

    /**
     * Models that may not fit in memory, to be put to the user as a warning. One-shot events: each
     * is delivered once, and the answer comes back through [onMemoryWarningDecision].
     */
    val modelMemoryWarnings: Flow<ModelMemoryWarning> get() = memoryConfirmation.requests

    /**
     * Whether a memory warning is actually waiting on an answer. False after process death, where
     * the dialog is restored by the framework but the load that raised it is long gone — the UI
     * uses this to drop a dialog whose answer nobody would receive.
     */
    val hasPendingMemoryWarning: Boolean get() = memoryConfirmation.hasOutstandingRequest

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        val savedPath = prefs()?.getString(KEY_MODEL_PATH, null)
        _savedModelPath.value = savedPath

        // The engine is loaded lazily by the backend, so from this screen it is always "ready".
        _engineState.value = EngineState.Initialized
        _modelLoadingState.value = modelStateFor(savedPath)
    }

    /**
     * The state describing the model that is actually configured. Built from the name persisted at
     * load time, so it needs no engine query.
     *
     * @param savedPath the stored model path, or null when none is configured
     */
    private fun modelStateFor(savedPath: String?): ModelLoadingState =
        if (savedPath != null) {
            ModelLoadingState.Loaded(getSavedModelName() ?: fallbackDisplayName(savedPath))
        } else {
            ModelLoadingState.Idle
        }

    /**
     * This plugin's own settings store — the same one `LocalLlmBackend` reads at request time, so
     * a model chosen here is the model that gets loaded.
     */
    private fun prefs(): SharedPreferences? =
        getContext()?.let(LocalLlmPreferences::of)

    /**
     * This plugin's IDE-surfaced log, so settings diagnostics land in the IDE's own log view rather
     * than only in logcat. Null before `initialize()` and in JVM tests.
     */
    private val logger: PluginLogger?
        get() = getContext()?.logger

    /** Human-readable name persisted alongside the model path at load time, if any. */
    fun getSavedModelName(): String? =
        prefs()?.getString(KEY_MODEL_NAME, null)?.takeIf { it.isNotBlank() }

    private fun saveLocalModelName(name: String?) {
        prefs()?.edit()?.putString(KEY_MODEL_NAME, name)?.apply()
    }

    /** Decoded last path segment — a cheap fallback that at least avoids raw %3A escapes. */
    fun fallbackDisplayName(uriOrPath: String): String = modelFiles.fallbackDisplayName(uriOrPath)

    fun saveLocalModelPath(path: String) {
        prefs()?.edit()?.putString(KEY_MODEL_PATH, path)?.apply()
        // Use postValue instead of value since this can be called from background threads
        _savedModelPath.postValue(path)
    }

    fun getLocalModelPath(): String? = prefs()?.getString(KEY_MODEL_PATH, null)

    fun saveLocalModelSha256(hash: String?) {
        prefs()?.edit()?.putString(KEY_MODEL_SHA256, hash?.trim() ?: "")?.apply()
    }

    fun getLocalModelSha256(): String? =
        prefs()?.getString(KEY_MODEL_SHA256, null)?.takeIf { it.isNotBlank() }

    fun setUseSimpleLocalPrompt(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_SIMPLE_PROMPT, enabled)?.apply()
    }

    fun isUseSimpleLocalPromptEnabled(): Boolean =
        prefs()?.getBoolean(KEY_SIMPLE_PROMPT, true) ?: true

    /**
     * Saves the selected model's path; the backend does the loading itself. That write is what
     * makes it load, so the memory pre-flight gates it: a model the user declines is never stored,
     * and therefore never loaded (ADFA-1798).
     *
     * @param uriString the selected model, as a `content://` URI or a filesystem path
     * @param context resolves the model's display name, size and header
     */
    fun loadModelFromUri(uriString: String, context: Context) {
        viewModelScope.launch(ioDispatcher) {
            _modelLoadingState.postValue(ModelLoadingState.Loading)

            try {
                // One lookup for both: the real file name to show, and the size to estimate from.
                val fileInfo = modelFiles.info(context, uriString)
                val fileName = fileInfo.displayName

                // Rejected up front, so no bad path is persisted or shown as "Loaded".
                if (!GgufFileInspector.looksLikeGguf(context.contentResolver, uriString)) {
                    _modelLoadingState.postValue(
                        ModelLoadingState.Error(
                            context.getString(R.string.error_model_not_gguf, fileName)
                        )
                    )
                    return@launch
                }

                if (!confirmMemoryHeadroom(uriString, fileInfo, context)) {
                    logger?.info("$TAG: model declined at the memory warning: $fileName")
                    // Never the configured model: re-checking it and declining must not revoke it.
                    if (uriString != getLocalModelPath()) {
                        modelFiles.releaseAccess(context, uriString)
                    }
                    restoreSavedModelState()
                    return@launch
                }

                // Persist the name before the path so the savedModelPath observer can read it.
                saveLocalModelName(fileName)
                saveLocalModelPath(uriString)

                // Nothing is loaded here; the engine reads this path when it needs the model.
                _modelLoadingState.postValue(ModelLoadingState.Loaded(fileName))

                logger?.debug("$TAG: model path saved: $uriString ($fileName)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.error("$TAG: error saving model path", e)
                _modelLoadingState.postValue(
                    ModelLoadingState.Error(
                        context.getString(R.string.error_model_save_failed, e.message.orEmpty())
                    )
                )
            }
        }
    }

    /**
     * Answers an outstanding [modelMemoryWarnings] question. Safe to call from the main thread, and
     * a no-op when nothing is waiting.
     *
     * @param proceed true to load the model anyway, false to abandon the selection
     */
    fun onMemoryWarningDecision(proceed: Boolean) {
        memoryConfirmation.answer(proceed)
    }

    /**
     * Checks the model against free RAM and, when it looks too large, asks the user whether to go
     * ahead. Fails OPEN: an unreadable size or header means no warning rather than a wrong one.
     * Prices the KV cache at the floor context, never at one derived from the free RAM it is then
     * compared against — that would make a larger granted context the thing that trips the warning,
     * and would move "needs X to run" between two selections of the same model.
     *
     * @return true to continue with this model
     */
    private suspend fun confirmMemoryHeadroom(
        uriString: String,
        fileInfo: ModelFileInfo,
        context: Context
    ): Boolean {
        val modelName = fileInfo.displayName
        // The floor is the least the load can use, so also the least this model can cost.
        val header = GgufHeaderReader.read { modelFiles.openStream(context, uriString) }
        val estimate = ModelMemoryEstimator.estimate(
            fileSizeBytes = fileInfo.sizeBytes,
            header = header,
            contextTokens = ContextSizePolicy.DEFAULT_CONTEXT_TOKENS,
            // The type does not depend on free RAM, so the load will pick this same one.
            kvType = ContextSizePolicy.chooseKvCache(header),
        )
        // Read last and never cached: the header parse above is blocking I/O over the model file,
        // and the user may have just closed apps to make room.
        val availableBytes = deviceMemory.availableBytes()

        return when (val verdict = ModelMemoryGate.evaluate(estimate, availableBytes)) {
            ModelMemoryGate.Verdict.Safe -> true

            ModelMemoryGate.Verdict.Unknown -> {
                val missing = if (estimate == null) "the model's size" else "free memory"
                logger?.warn("$TAG: could not read $missing; skipping the pre-flight for $modelName")
                true
            }

            is ModelMemoryGate.Verdict.Risky -> {
                logger?.warn(
                    "$TAG: $modelName may not fit: needs ${ByteSize.format(verdict.estimate.loadBytes)}" +
                        " + ${ByteSize.format(verdict.estimate.runBytes)} to run," +
                        " ${ByteSize.format(verdict.availableBytes)} free (${verdict.severity})"
                )
                memoryConfirmation.ask(
                    ModelMemoryWarning(
                        modelName = modelName,
                        loadBytes = verdict.estimate.loadBytes,
                        runBytes = verdict.estimate.runBytes,
                        availableBytes = verdict.availableBytes,
                        severity = verdict.severity,
                    )
                )
            }
        }
    }

    /**
     * Republishes the model that is actually configured, so abandoning a selection leaves the
     * screen describing the previous model rather than the one that was never stored.
     */
    private fun restoreSavedModelState() {
        _modelLoadingState.postValue(modelStateFor(getLocalModelPath()))
    }
}
