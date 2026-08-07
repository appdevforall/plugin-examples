package com.itsaky.androidide.plugins.aiassistant.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.aiassistant.gemini.CatalogResult
import com.itsaky.androidide.plugins.aiassistant.gemini.GeminiCatalogGateway
import com.itsaky.androidide.plugins.aiassistant.gemini.KeyVerification
import com.itsaky.androidide.plugins.aiassistant.gemini.ReflectiveGeminiCatalogGateway
import com.itsaky.androidide.plugins.aiassistant.gemini.toKeyVerification
import com.itsaky.androidide.plugins.aiassistant.memory.DeviceMemory
import com.itsaky.androidide.plugins.aiassistant.memory.ModelMemoryEstimator
import com.itsaky.androidide.plugins.aiassistant.memory.ModelMemoryGate
import com.itsaky.androidide.plugins.aiassistant.memory.SystemDeviceMemory
import com.itsaky.androidide.plugins.aiassistant.security.SecureApiKeyStore
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.util.ByteSize
import com.itsaky.androidide.plugins.aiassistant.util.ContentModelFileSource
import com.itsaky.androidide.plugins.aiassistant.util.GgufFileInspector
import com.itsaky.androidide.plugins.aiassistant.util.GgufHeaderReader
import com.itsaky.androidide.plugins.aiassistant.util.ModelFileInfo
import com.itsaky.androidide.plugins.aiassistant.util.ModelFileSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger

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
 * Available AI backends.
 */
enum class AiBackend(val displayName: String) {
    LOCAL_LLM("Local LLM"),
    GEMINI("Gemini API")
}

/**
 * Gemini models to offer, plus whether they came from a live catalog fetch (vs the fallback list).
 * Migrate a saved-but-missing model off the list only when [isLive] is true.
 *
 * @param models model ids to display in the picker
 * @param isLive true if [models] is a confirmed live catalog, false for the offline fallback
 */
data class GeminiModelOptions(val models: List<String>, val isLive: Boolean)

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
 * @param deviceMemory free-RAM reading for the pre-flight; null builds the live one, which cannot
 *   be a default argument because it needs [logger], and a default cannot reach an instance member
 * @param modelFiles reads a selected model's name, size and bytes; null builds the live one
 */
class AiSettingsViewModel(
    private val getContext: () -> PluginContext?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val catalogGateway: GeminiCatalogGateway = ReflectiveGeminiCatalogGateway(),
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
        private const val TAG = "AiSettingsViewModel"

        /** Default selection; kept in sync with GeminiBackend.DEFAULT_MODEL. */
        private const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

        /** Shown only when the live catalog can't be fetched — current models, no retired ones. */
        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        )
    }

    /**
     * True between tapping *Get API Key* and the settings screen's next resume, so the UI can
     * point at the next step once the user is back from AI Studio. Held here rather than on the
     * fragment so a rotation while the browser is in front doesn't reset it and swallow the hint.
     */
    var sentUserToAiStudio: Boolean = false

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
        val prefs = getPluginPrefs()
        val savedPath = prefs?.getString("local_llm_model_path", null)
        _savedModelPath.value = savedPath

        // For plugin, engine is always "ready" since it's managed by ai-core plugin
        _engineState.value = EngineState.Initialized
        _modelLoadingState.value = modelStateFor(savedPath)
    }

    /**
     * The state describing the model that is actually configured. Built from the name persisted at
     * load time, so it needs no provider query.
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
     * This plugin's settings store — and, for the Gemini keys, ai-core's too.
     *
     * ai-core resolves this plugin's `PluginContext` and asks for the same name, so both sides
     * share one process-wide `SharedPreferencesImpl`: writes here need no flush to be visible.
     */
    private fun getPluginPrefs() = getContext()?.getPluginSharedPreferences("AgentSettings")

    /**
     * This plugin's IDE-surfaced log, so settings diagnostics land in the IDE's own log view rather
     * than only in logcat. Null before `initialize()` and in JVM tests.
     */
    private val logger: PluginLogger?
        get() = getContext()?.logger

    /** Human-readable name persisted alongside the model path at load time, if any. */
    fun getSavedModelName(): String? =
        getPluginPrefs()?.getString("local_llm_model_name", null)?.takeIf { it.isNotBlank() }

    private fun saveLocalModelName(name: String?) {
        getPluginPrefs()?.edit()?.putString("local_llm_model_name", name)?.apply()
    }

    /** Decoded last path segment — a cheap fallback that at least avoids raw %3A escapes. */
    fun fallbackDisplayName(uriOrPath: String): String = modelFiles.fallbackDisplayName(uriOrPath)

    fun getAvailableBackends(): List<AiBackend> = AiBackend.entries

    fun saveBackend(backend: AiBackend) {
        getPluginPrefs()?.edit()?.apply {
            putString("ai_backend_preference", backend.name)
            apply()
        }
    }

    fun getCurrentBackend(): AiBackend {
        val backendName = getPluginPrefs()?.getString("ai_backend_preference", "LOCAL_LLM")
        return try {
            AiBackend.valueOf(backendName ?: "LOCAL_LLM")
        } catch (e: Exception) {
            AiBackend.LOCAL_LLM
        }
    }

    fun saveLocalModelPath(path: String) {
        getPluginPrefs()?.edit()?.apply {
            putString("local_llm_model_path", path)
            apply()
        }
        // Use postValue instead of value since this can be called from background threads
        _savedModelPath.postValue(path)
    }

    fun getLocalModelPath(): String? {
        return getPluginPrefs()?.getString("local_llm_model_path", null)
    }

    fun saveLocalModelSha256(hash: String?) {
        getPluginPrefs()?.edit()?.apply {
            putString("local_llm_model_sha256", hash?.trim() ?: "")
            apply()
        }
    }

    fun getLocalModelSha256(): String? {
        return getPluginPrefs()?.getString("local_llm_model_sha256", null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setUseSimpleLocalPrompt(enabled: Boolean) {
        getPluginPrefs()?.edit()?.apply {
            putBoolean("use_simple_local_prompt", enabled)
            apply()
        }
    }

    fun isUseSimpleLocalPromptEnabled(): Boolean {
        return getPluginPrefs()?.getBoolean("use_simple_local_prompt", true) ?: true
    }

    /**
     * Check whether [apiKey] actually works, without storing it anywhere.
     *
     * Asks ai-core to list the models the candidate key can reach; see [KeyVerification] for what
     * each verdict establishes. Run this *before* [saveGeminiApiKey]. The key is never logged.
     *
     * @param apiKey the candidate key as typed, trimmed here
     * @return the verdict; [KeyVerification.Unknown] when nothing could be established
     */
    suspend fun verifyGeminiKey(apiKey: String): KeyVerification = withContext(ioDispatcher) {
        val candidate = apiKey.trim()
        if (candidate.isEmpty()) return@withContext KeyVerification.Rejected
        val result = try {
            catalogGateway.listModels(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort net: a verification crash must never be mistaken for a pass.
            logger?.error("$TAG: Gemini key verification failed unexpectedly", e)
            CatalogResult.Failed(e)
        }
        result.toKeyVerification().also { verification ->
            // Diagnostic only: saving a key is not a request for a catalog, so the UI omits this.
            if (verification is KeyVerification.Verified) {
                logger?.debug(
                    "$TAG: Gemini key verified against ${verification.modelCount} " +
                        "chat-capable models"
                )
            }
        }
    }

    /**
     * Encrypts [apiKey] via [SecureApiKeyStore] and persists only the ciphertext to private prefs,
     * off the main thread. Nothing is written on failure. Kept separate from [verifyGeminiKey]: a
     * rejected key never reaches here, and an unverifiable one only after the user says so.
     *
     * @param apiKey the plaintext key to store (trimmed before encryption)
     * @param verified true when [verifyGeminiKey] confirmed this key; recorded in the same write so
     *   the flag can never outlive or precede the key it describes
     * @return true only if the key was both encrypted and persisted
     */
    suspend fun saveGeminiApiKey(apiKey: String, verified: Boolean = false): Boolean =
        withContext(ioDispatcher) {
            // Checked first, or the UI would claim an unwritten key was saved.
            val prefs = getPluginPrefs()
            if (prefs == null) {
                logger?.error("$TAG: cannot save Gemini API key: plugin preferences unavailable")
                return@withContext false
            }
            val encrypted = try {
                SecureApiKeyStore.encrypt(apiKey.trim())
            } catch (e: Exception) {
                logger?.error("$TAG: failed to encrypt Gemini API key", e)
                return@withContext false
            }
            // commit(), not apply(): only a synchronous write can honestly return "persisted".
            prefs.edit()
                .putString("gemini_api_key", encrypted)
                .putLong("gemini_api_key_timestamp", System.currentTimeMillis())
                .putBoolean("gemini_api_key_verified", verified)
                .commit()
        }

    /**
     * Whether the stored key was confirmed working by Google when it was saved.
     *
     * False for a key kept after an inconclusive check, so the status line can say "saved" without
     * claiming "verified". Raw pref only, so safe on the main thread.
     */
    fun isGeminiKeyVerified(): Boolean =
        getPluginPrefs()?.getBoolean("gemini_api_key_verified", false) ?: false

    /**
     * Decrypt the stored key off the main thread (Keystore IPC + AES/GCM), upgrading a
     * pre-encryption plaintext key to ciphertext in passing so existing installs actually
     * end up encrypted rather than waiting for the user to re-enter the key.
     */
    suspend fun getGeminiApiKey(): String? = withContext(ioDispatcher) {
        SecureApiKeyStore.readAndMigrate(getPluginPrefs(), "gemini_api_key")
    }

    /**
     * True when a Gemini key is present on disk, whether or not it can still be decrypted. Lets
     * the UI tell "nothing was saved" from "the Keystore entry is gone" — [getGeminiApiKey] is
     * null for both. Raw pref only, so no Keystore IPC and safe on the main thread.
     */
    fun hasStoredGeminiApiKey(): Boolean =
        !getPluginPrefs()?.getString("gemini_api_key", null).isNullOrBlank()

    fun getGeminiApiKeySaveTimestamp(): Long {
        return getPluginPrefs()?.getLong("gemini_api_key_timestamp", 0L) ?: 0L
    }

    fun clearGeminiApiKey() {
        getPluginPrefs()?.edit()?.apply {
            remove("gemini_api_key")
            remove("gemini_api_key_timestamp")
            // Removed with the key, or the next saved key would inherit this one's verdict.
            remove("gemini_api_key_verified")
            apply()
        }
    }

    fun saveGeminiModel(model: String) {
        getPluginPrefs()?.edit()?.apply {
            putString("gemini_model", model)
            apply()
        }
    }

    fun getGeminiModel(): String {
        return getPluginPrefs()?.getString("gemini_model", DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
    }

    private val _geminiModels = MutableLiveData(GeminiModelOptions(emptyList(), isLive = false))
    val geminiModels: LiveData<GeminiModelOptions> get() = _geminiModels

    private val _geminiModelsLoading = MutableLiveData<Boolean>(false)
    val geminiModelsLoading: LiveData<Boolean> get() = _geminiModelsLoading

    /**
     * Ask ai-core's Gemini backend for the models the current API key can actually
     * use, and publish them to [geminiModels]. Falls back to [FALLBACK_MODELS] (current
     * models only — never a retired one) when there is no key, no backend, or the live
     * lookup fails, so the picker is never populated with a model that would 404.
     */
    fun fetchGeminiModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _geminiModelsLoading.postValue(true)

            try {
                val apiKey = getGeminiApiKey()?.trim()
                if (apiKey.isNullOrBlank()) {
                    logger?.warn("$TAG: no Gemini API key saved; showing fallback models")
                    _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
                    return@launch
                }

                when (val result = catalogGateway.listModelsForSavedKey()) {
                    is CatalogResult.Success -> {
                        if (result.models.isEmpty()) {
                            logger?.warn("$TAG: live model list empty; showing fallback models")
                            _geminiModels.postValue(
                                GeminiModelOptions(FALLBACK_MODELS, isLive = false)
                            )
                        } else {
                            logger?.debug("$TAG: fetched ${result.models.size} Gemini models")
                            _geminiModels.postValue(
                                GeminiModelOptions(result.models, isLive = true)
                            )
                        }
                    }
                    // Logged by the gateway; degrade to current-models-only, never a 404 model.
                    CatalogResult.NoBackend, is CatalogResult.Failed ->
                        _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.error("$TAG: error fetching Gemini models", e)
                _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
            } finally {
                _geminiModelsLoading.postValue(false)
            }
        }
    }

    /**
     * Saves the selected model's path; ai-core's `LocalLlmBackend` does the loading itself. That
     * write is what makes it load, so the memory pre-flight gates it: a model the user declines is
     * never stored, and therefore never loaded (ADFA-1798).
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
                        ModelLoadingState.Error(context.getString(R.string.error_model_not_gguf, fileName))
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

                // Nothing is loaded here; ai-core reads this path when it needs the model.
                _modelLoadingState.postValue(
                    ModelLoadingState.Loaded(fileName)
                )

                logger?.debug("$TAG: model path saved: $uriString ($fileName)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger?.error("$TAG: error saving model path", e)
                _modelLoadingState.postValue(
                    ModelLoadingState.Error("Failed to save model path: ${e.message}")
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
     *
     * @return true to continue with this model
     */
    private suspend fun confirmMemoryHeadroom(
        uriString: String,
        fileInfo: ModelFileInfo,
        context: Context
    ): Boolean {
        val modelName = fileInfo.displayName
        val estimate = ModelMemoryEstimator.estimate(
            fileSizeBytes = fileInfo.sizeBytes,
            header = GgufHeaderReader.read { modelFiles.openStream(context, uriString) },
        )
        // Read last and never cached: the user may have just closed apps to make room.
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
