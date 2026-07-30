package com.itsaky.androidide.plugins.aiassistant.viewmodel

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.aiassistant.security.SecureApiKeyStore
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.itsaky.androidide.plugins.PluginContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

class AiSettingsViewModel(
    private val getContext: () -> PluginContext?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiSettingsViewModel"

        /** Default selection; kept in sync with GeminiBackend.DEFAULT_MODEL. */
        private const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

        /**
         * Failsafe cap on the cross-plugin model listing, above ai-core's own per-request budget
         * (15 s connect + 15 s read, paginated) so a slow-but-live fetch is never truncated.
         * Bounds a future that may never complete; it is not a network timeout.
         */
        private const val LIST_MODELS_TIMEOUT_SECONDS = 60L

        /** Shown only when the live catalog can't be fetched — current models, no retired ones. */
        private val FALLBACK_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
        )
    }

    private val _savedModelPath = MutableLiveData<String?>(null)
    val savedModelPath: LiveData<String?> get() = _savedModelPath

    private val _modelLoadingState = MutableLiveData<ModelLoadingState>(ModelLoadingState.Idle)
    val modelLoadingState: LiveData<ModelLoadingState> get() = _modelLoadingState

    private val _engineState = MutableLiveData<EngineState>(EngineState.Initialized)
    val engineState: LiveData<EngineState> get() = _engineState

    init {
        checkInitialState()
    }

    private fun checkInitialState() {
        val prefs = getPluginPrefs()
        val savedPath = prefs?.getString("local_llm_model_path", null)
        _savedModelPath.value = savedPath

        // For plugin, engine is always "ready" since it's managed by ai-core plugin
        _engineState.value = EngineState.Initialized
        // Reflect a previously selected model so it survives closing/reopening settings,
        // using the display name persisted at load time (no content-provider query here).
        _modelLoadingState.value = if (savedPath != null) {
            ModelLoadingState.Loaded(getSavedModelName() ?: fallbackDisplayName(savedPath))
        } else {
            ModelLoadingState.Idle
        }
    }

    private fun getPluginPrefs() = getContext()?.getPluginSharedPreferences("AgentSettings")

    /** Human-readable name persisted alongside the model path at load time, if any. */
    fun getSavedModelName(): String? =
        getPluginPrefs()?.getString("local_llm_model_name", null)?.takeIf { it.isNotBlank() }

    private fun saveLocalModelName(name: String?) {
        getPluginPrefs()?.edit()?.putString("local_llm_model_name", name)?.apply()
    }

    /** Decoded last path segment — a cheap fallback that at least avoids raw %3A escapes. */
    fun fallbackDisplayName(uriOrPath: String): String =
        (try { android.net.Uri.decode(uriOrPath) } catch (e: Exception) { uriOrPath }).substringAfterLast('/')

    /**
     * Resolve the real file name for a selected model. For a `content://` URI this queries the
     * document provider's [OpenableColumns.DISPLAY_NAME] (e.g. "Llama-3.2-1B.gguf"); otherwise,
     * and on any failure, it falls back to the decoded last path segment. Do NOT call on the main
     * thread — the provider query can block.
     */
    private fun resolveDisplayName(uriString: String): String {
        if (uriString.startsWith("content://")) {
            try {
                val uri = android.net.Uri.parse(uriString)
                getContext()?.androidContext?.contentResolver
                    ?.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                            c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not resolve display name for $uriString", e)
            }
        }
        return fallbackDisplayName(uriString)
    }

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
     * Encrypts [apiKey] via [SecureApiKeyStore] and persists only the ciphertext to private
     * prefs, off the main thread (Keystore IPC + AES/GCM). Nothing is written on failure.
     *
     * @param apiKey the plaintext key to store (trimmed before encryption)
     * @return true only if the key was both encrypted and persisted
     */
    suspend fun saveGeminiApiKey(apiKey: String): Boolean = withContext(ioDispatcher) {
        // Checked first: returning true here would have the UI claim an unwritten key was saved.
        val prefs = getPluginPrefs()
        if (prefs == null) {
            android.util.Log.e(TAG, "Cannot save Gemini API key: plugin preferences unavailable")
            return@withContext false
        }
        val encrypted = try {
            SecureApiKeyStore.encrypt(apiKey.trim())
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to encrypt Gemini API key", e)
            return@withContext false
        }
        prefs.edit()
            .putString("gemini_api_key", encrypted)
            .putLong("gemini_api_key_timestamp", System.currentTimeMillis())
            .apply()
        true
    }

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
                    android.util.Log.w(TAG, "No Gemini API key saved; showing fallback models")
                    _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
                    return@launch
                }

                val llmService = SharedServices.get(LlmInferenceService::class.java)
                val geminiBackend = llmService?.getBackend("gemini")
                if (geminiBackend == null) {
                    android.util.Log.e(TAG, "Gemini backend not available")
                    _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
                    return@launch
                }

                val models = listModelsViaBackend(geminiBackend)
                if (models.isEmpty()) {
                    android.util.Log.w(TAG, "Live model list empty; showing fallback models")
                    _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
                } else {
                    android.util.Log.d(TAG, "Fetched ${models.size} Gemini models")
                    _geminiModels.postValue(GeminiModelOptions(models, isLive = true))
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error fetching Gemini models", e)
                _geminiModels.postValue(GeminiModelOptions(FALLBACK_MODELS, isLive = false))
            } finally {
                _geminiModelsLoading.postValue(false)
            }
        }
    }

    /**
     * Ask ai-core's Gemini backend for its live model catalog.
     *
     * `listModels()` isn't on the shared [LlmInferenceService.LlmBackend] interface, so reflection
     * is the only way across the plugin classloader boundary — an unchecked contract, hence the
     * loud log when it breaks rather than a silent fall back to [FALLBACK_MODELS].
     *
     * @param backend the resolved "gemini" backend instance from [SharedServices]
     * @return the live catalog, or an empty list when unavailable
     */
    private fun listModelsViaBackend(backend: Any): List<String> {
        val method = try {
            backend.javaClass.getMethod("listModels")
        } catch (e: NoSuchMethodException) {
            android.util.Log.e(
                TAG,
                "ai-core's ${backend.javaClass.name} has no listModels(): the cross-plugin " +
                    "contract changed. Expected `fun listModels(): CompletableFuture<List<String>>`.",
                e
            )
            return emptyList()
        }
        val result = method.invoke(backend)

        @Suppress("UNCHECKED_CAST")
        val future = result as? CompletableFuture<List<String>>
        if (future == null) {
            android.util.Log.e(
                TAG,
                "listModels() returned ${result?.javaClass?.name}, expected CompletableFuture"
            )
            return emptyList()
        }
        // Bounded: a future from ai-core's already-cancelled scope would never complete.
        return try {
            future.get(LIST_MODELS_TIMEOUT_SECONDS, TimeUnit.SECONDS).orEmpty()
        } catch (e: TimeoutException) {
            future.cancel(true)
            android.util.Log.e(
                TAG,
                "listModels() did not complete within ${LIST_MODELS_TIMEOUT_SECONDS}s; " +
                    "is ai-core still active?",
                e
            )
            emptyList()
        }
    }

    /**
     * Load a model from URI.
     * In the plugin context, we just save the path - the actual loading
     * is handled by the ai-core plugin's LocalLlmBackend.
     */
    fun loadModelFromUri(uriString: String, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _modelLoadingState.postValue(ModelLoadingState.Loading)

            try {
                // Resolve the real file name (not the raw content-URI doc id) for display.
                val fileName = resolveDisplayName(uriString)

                // Persist the name before the path so the savedModelPath observer can read it.
                saveLocalModelName(fileName)
                saveLocalModelPath(uriString)

                // In plugin context, we don't directly load the model
                // The ai-core plugin will load it when needed
                _modelLoadingState.postValue(
                    ModelLoadingState.Loaded(fileName)
                )

                android.util.Log.d(TAG, "Model path saved: $uriString ($fileName)")
            } catch (e: Exception) {
                android.util.Log.e("AiSettingsViewModel", "Error saving model path", e)
                _modelLoadingState.postValue(
                    ModelLoadingState.Error("Failed to save model path: ${e.message}")
                )
            }
        }
    }
}
