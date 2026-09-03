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

    /**
     * A model is configured but its file can no longer be read — deleted, unmounted, or the read
     * grant revoked. Distinct from [Error]: nothing failed here, the selection simply went stale,
     * and the screen has to say so rather than keep reporting the model as loaded (ADFA-5253).
     */
    data class Unavailable(val modelName: String) : ModelLoadingState()

    data class Error(val message: String) : ModelLoadingState()
}

/**
 * Whether this backend can serve a request. The engine itself is loaded lazily on the first
 * request, so there is no engine to interrogate here and readiness is a statement about the
 * configured model: without one that can actually be loaded there is nothing to be ready for.
 * Derived from [ModelLoadingState] — see [LocalLlmSettingsViewModel.engineStateFor].
 */
sealed class EngineState {
    /** No model is configured yet, so the engine has nothing to load. */
    object NoModel : EngineState()

    /** A model is configured but its file cannot be read; the engine cannot load it. */
    object ModelUnavailable : EngineState()

    object Initializing : EngineState()
    object Initialized : EngineState()
    data class Error(val message: String) : EngineState()
}

/**
 * Everything this pane draws, as one value: the configured model, how it is doing, and the
 * readiness that follows from it. One container rather than three streams, so the three lines are
 * published in a single dispatch and can never describe different models mid-update.
 *
 * @param savedModelPath the configured model, as a `content://` URI or a path; null when unset
 * @param savedModelName the display name for [savedModelPath]
 */
data class LocalLlmSettingsState(
    val savedModelPath: String? = null,
    val savedModelName: String? = null,
    val model: ModelLoadingState = ModelLoadingState.Idle,
    val engine: EngineState = EngineState.NoModel,
)

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

    /**
     * The authoritative state, kept here rather than read back from [_state]: `postValue` publishes
     * asynchronously, so a background update that read `_state.value` would compute its copy from
     * a version two updates old and silently drop the ones in between.
     */
    @Volatile private var current = LocalLlmSettingsState()

    private val _state = MutableLiveData(current)
    val state: LiveData<LocalLlmSettingsState> get() = _state

    /**
     * Applies [transform] to the state and publishes the result. Synchronized because the memory
     * pre-flight, the availability re-check and a load can all be in flight at once.
     */
    @Synchronized
    private fun update(transform: (LocalLlmSettingsState) -> LocalLlmSettingsState) {
        current = transform(current)
        _state.postValue(current)
    }

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
        val modelState = modelStateFor(savedPath)
        // Optimistic: the file has not been read yet. refreshSavedModelAvailability() corrects it.
        update {
            LocalLlmSettingsState(
                savedModelPath = savedPath,
                savedModelName = savedPath?.let { displayNameFor(it) },
                model = modelState,
                engine = engineStateFor(modelState) ?: EngineState.Initialized,
            )
        }
        refreshSavedModelAvailability()
    }

    /**
     * Re-checks that the configured model is still readable and downgrades the status to
     * [ModelLoadingState.Unavailable] when it is not. Call whenever this screen becomes visible:
     * the file lives outside the IDE, so it can be deleted or unmounted between two visits, and the
     * stored path on its own would keep claiming the model is loaded (ADFA-5253).
     */
    fun refreshSavedModelAvailability() {
        val savedPath = getLocalModelPath() ?: return
        val context = getContext()?.androidContext ?: return

        viewModelScope.launch(ioDispatcher) {
            val readable = modelFiles.isReadable(context, savedPath)

            // A selection made while the check ran owns the status now; leave it to that load.
            if (getLocalModelPath() != savedPath) return@launch
            if (current.model is ModelLoadingState.Loading) return@launch

            if (readable) {
                // Only ever clears a stale "unavailable": a live Error is about this same model.
                if (current.model is ModelLoadingState.Unavailable) {
                    publishModelState(modelStateFor(savedPath))
                }
            } else {
                logger?.warn("$TAG: the configured model can no longer be read: $savedPath")
                publishModelState(
                    ModelLoadingState.Unavailable(displayNameFor(savedPath))
                )
            }
        }
    }

    /**
     * Publishes a model status together with the engine readiness that follows from it, in one
     * dispatch, so the screen can never draw a model and a readiness that disagree.
     */
    private fun publishModelState(model: ModelLoadingState) {
        update { it.copy(model = model, engine = engineStateFor(model) ?: it.engine) }
    }

    /**
     * Publishes a selection that was not kept: the model line says what went wrong with the pick,
     * the engine line keeps describing the *configured* model. A pick of another file hands the
     * engine back untouched; "Load from saved" re-picks the configured one, so its failure counts.
     *
     * @param uriString the pick that was abandoned
     * @param model what to say about it
     * @param engineBefore the engine status from before the selection started
     */
    private fun publishAbandonedSelection(
        uriString: String,
        model: ModelLoadingState,
        engineBefore: EngineState,
    ) {
        val engine =
            if (uriString == getLocalModelPath()) engineStateFor(model) ?: engineBefore
            else engineBefore

        update { it.copy(model = model, engine = engine) }
    }

    /**
     * Engine readiness implied by a model status, or null to leave the engine's status alone.
     *
     * @param state the model status just published
     */
    private fun engineStateFor(state: ModelLoadingState): EngineState? = when (state) {
        is ModelLoadingState.Idle -> EngineState.NoModel
        is ModelLoadingState.Loading -> EngineState.Initializing
        is ModelLoadingState.Loaded -> EngineState.Initialized
        is ModelLoadingState.Unavailable -> EngineState.ModelUnavailable
        // A rejected *selection* says nothing about the model that is actually configured, which
        // this leaves in place — so it must not restate that model's readiness either way.
        is ModelLoadingState.Error -> null
    }

    /**
     * The state describing the model that is actually configured. Built from the name persisted at
     * load time, so it needs no engine query.
     *
     * @param savedPath the stored model path, or null when none is configured
     */
    private fun modelStateFor(savedPath: String?): ModelLoadingState =
        if (savedPath != null) {
            ModelLoadingState.Loaded(displayNameFor(savedPath))
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
    private fun getSavedModelName(): String? =
        prefs()?.getString(KEY_MODEL_NAME, null)?.takeIf { it.isNotBlank() }

    private fun saveLocalModelName(name: String?) {
        prefs()?.edit()?.putString(KEY_MODEL_NAME, name)?.apply()
        update { it.copy(savedModelName = name) }
    }

    /** Decoded last path segment — a cheap fallback that at least avoids raw %3A escapes. */
    private fun fallbackDisplayName(uriOrPath: String): String =
        modelFiles.fallbackDisplayName(uriOrPath)

    /** The name to show for a configured model: the one persisted at load time, else the path's. */
    private fun displayNameFor(uriOrPath: String): String =
        getSavedModelName() ?: fallbackDisplayName(uriOrPath)

    fun saveLocalModelPath(path: String) {
        prefs()?.edit()?.putString(KEY_MODEL_PATH, path)?.apply()
        update { it.copy(savedModelPath = path) }
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
     * The read grant is made persistable first: the model is read in place rather than copied, so
     * without a durable grant the stored path would stop resolving at the next restart (ADFA-5253).
     *
     * @param uriString the selected model, as a `content://` URI or a filesystem path
     */
    fun loadModelFromUri(uriString: String) {
        // This plugin's own context, not the caller's: a UI Context captured by a coroutine that
        // outlives the fragment would hold the Activity, and only this one resolves the plugin's
        // own resources for the messages below.
        val context = getContext()?.androidContext ?: run {
            logger?.error("$TAG: no plugin context; cannot select $uriString")
            return
        }

        // Taken before the Loading below overwrites it; an abandoned pick puts it back.
        val stateBefore = current

        viewModelScope.launch(ioDispatcher) {
            publishModelState(ModelLoadingState.Loading)

            try {
                // Taken before the first read, so every step below works off the durable grant.
                if (!modelFiles.persistAccess(context, uriString)) {
                    // Readable now through the picker's own grant, but not after a restart. Better
                    // to load it and say so later than to refuse a model the user just picked.
                    logger?.warn("$TAG: no persistable read grant for $uriString")
                }

                // One lookup for both: the real file name to show, and the size to estimate from.
                val fileInfo = modelFiles.info(context, uriString)
                val fileName = fileInfo.displayName

                // Checked before the GGUF sniff so a model that is simply gone — the "Load from
                // saved" case after the file was deleted — is not reported as a corrupt one.
                if (!modelFiles.isReadable(context, uriString)) {
                    releaseUnkeptGrant(context, uriString)
                    publishAbandonedSelection(
                        uriString,
                        ModelLoadingState.Unavailable(fileName),
                        stateBefore.engine,
                    )
                    return@launch
                }

                // Rejected up front, so no bad path is persisted or shown as "Loaded".
                if (!GgufFileInspector.looksLikeGguf(context.contentResolver, uriString)) {
                    releaseUnkeptGrant(context, uriString)
                    publishAbandonedSelection(
                        uriString,
                        ModelLoadingState.Error(
                            context.getString(R.string.error_model_not_gguf, fileName)
                        ),
                        stateBefore.engine,
                    )
                    return@launch
                }

                if (!confirmMemoryHeadroom(uriString, fileInfo, context)) {
                    logger?.info("$TAG: model declined at the memory warning: $fileName")
                    releaseUnkeptGrant(context, uriString)
                    restoreStateBefore(stateBefore)
                    return@launch
                }

                // The model being replaced is no longer read by anything, and grants are capped.
                val replaced = getLocalModelPath()
                if (replaced != null && replaced != uriString) {
                    modelFiles.releaseAccess(context, replaced)
                }

                // Persist the name before the path so the savedModelPath observer can read it.
                saveLocalModelName(fileName)
                saveLocalModelPath(uriString)

                // Nothing is loaded here; the engine reads this path when it needs the model.
                publishModelState(ModelLoadingState.Loaded(fileName))

                logger?.debug("$TAG: model path saved: $uriString ($fileName)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.error("$TAG: error saving model path", e)
                publishAbandonedSelection(
                    uriString,
                    ModelLoadingState.Error(
                        context.getString(R.string.error_model_save_failed, e.message.orEmpty())
                    ),
                    stateBefore.engine,
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
        val header = GgufHeaderReader.read { modelFiles.openStream(context, uriString) }
        // Prices at the floor by construction — it takes no context and no free-RAM figure at all,
        // and names the cache type the load will pick, which depends only on the header.
        val estimate = ModelMemoryEstimator.estimateForSelection(fileInfo.sizeBytes, header)
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
     * Gives back the grant taken for a selection that was not kept, so an abandoned pick does not
     * hold a slot in the capped grant table.
     *
     * Never touches the configured model: re-checking it and abandoning that check must leave the
     * model that is actually in use readable.
     */
    private fun releaseUnkeptGrant(context: Context, uriString: String) {
        if (uriString != getLocalModelPath()) {
            modelFiles.releaseAccess(context, uriString)
        }
    }

    /**
     * Puts the screen back as it was before a selection the user declined outright. Restores both
     * lines rather than re-deriving them: a configured model that was already unreachable must
     * stay reported that way.
     *
     * @param stateBefore the state captured before the selection started
     */
    private fun restoreStateBefore(stateBefore: LocalLlmSettingsState) {
        update { stateBefore }
    }
}
