package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of LlmInferenceService.
 * Manages LLM backends and delegates generation requests to registered backends.
 */
class LlmInferenceServiceImpl : LlmInferenceService {

    private val backends = ConcurrentHashMap<String, LlmBackend>()
    @Volatile private var currentGeneration: CompletableFuture<LlmResponse>? = null

    override fun registerBackend(backend: LlmBackend) {
        backends[backend.getId()] = backend
    }

    override fun unregisterBackend(backendId: String) {
        backends.remove(backendId)
    }

    override fun getAvailableBackends(): List<LlmBackend> {
        return backends.values.toList()
    }

    override fun getBackend(backendId: String): LlmBackend? {
        return backends[backendId]
    }

    override fun isBackendAvailable(backendId: String): Boolean {
        val backend = backends[backendId]
        return backend != null && backend.isAvailable()
    }

    override fun generateCompletion(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        val effectiveId = effectiveBackendId(config.backendId)
        val backend = backends[effectiveId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' is not available")
            )
        }

        config.backendId = effectiveId

        val future = backend.generate(prompt, config)
        currentGeneration = future
        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        val effectiveId = effectiveBackendId(config.backendId)
        val backend = backends[effectiveId]
        if (backend == null) {
            callback.onError("Backend '$effectiveId' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '$effectiveId' is not available")
            return
        }

        backend.generateStreaming(prompt, config, callback)
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        val effectiveId = effectiveBackendId(config.backendId)
        val backend = backends[effectiveId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' is not available")
            )
        }

        val future = backend.generateWithHistory(history, prompt, config)
        currentGeneration = future
        return future
    }

    override fun generateStreamingWithTools(
        prompt: String,
        history: List<ChatMessage>,
        config: LlmConfig,
        tools: List<ToolDefinition>,
        callback: ToolStreamCallback
    ) {
        val effectiveId = effectiveBackendId(config.backendId)
        val backend = backends[effectiveId]
        if (backend == null) {
            callback.onError("Backend '$effectiveId' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '$effectiveId' is not available")
            return
        }

        // Check if backend supports tool calling (only Gemini for now)
        if (backend !is GeminiBackend) {
            // Fallback to streaming without tools for non-Gemini backends
            val streamCallback = object : StreamCallback {
                override fun onToken(token: String) = callback.onToken(token)
                override fun onComplete(response: LlmResponse) = callback.onComplete(response)
                override fun onError(error: String) = callback.onError(error)
            }
            if (backend is LocalLlmBackend) {
                backend.generateStreamingWithHistory(history, prompt, config, streamCallback)
            } else {
                backend.generateStreaming(prompt, config, streamCallback)
            }
            return
        }

        // Delegate to Gemini backend with tool support (smart-cast by the guard above)
        backend.generateStreamingWithTools(prompt, history, config, tools, callback)
    }

    override fun getEmbeddings(text: String, backendId: String): CompletableFuture<FloatArray> {
        // Stub implementation - embeddings not needed for Phase 3
        return CompletableFuture.completedFuture(FloatArray(0))
    }

    /**
     * Resolves the backend id a request should run on. An explicit id is returned unchanged
     * (so the caller keeps its "not found"/"not available" errors); [AiBackend.AUTO] is
     * resolved to the user-selected backend, then to any available backend, so callers can
     * defer backend choice to AI Core instead of hardcoding one.
     *
     * @param requestedId the id from [LlmConfig.backendId]
     * @return the id to route to; for AUTO with nothing available, the selected backend's id
     *   so the downstream "not available" error stays meaningful
     */
    private fun effectiveBackendId(requestedId: String): String {
        if (requestedId != AiBackend.AUTO) return requestedId
        val preferred = AiBackend.fromPreference(readSelectedBackendPreference())
        return backends[preferred.id]?.takeIf { it.isAvailable() }?.getId()
            ?: backends.values.firstOrNull { it.isAvailable() }?.getId()
            ?: preferred.id
    }

    /**
     * Reads the backend the user selected in the AI Assistant settings. The preference is
     * namespaced to the AI Assistant plugin, so it is only reachable through that plugin's
     * [PluginContext], which it publishes to [SharedServices].
     *
     * @return the stored preference value, or null when AI Assistant is absent or unset
     */
    private fun readSelectedBackendPreference(): String? =
        SharedServices.get(PluginContext::class.java)
            ?.getPluginSharedPreferences(AiBackend.PREFERENCE_FILE)
            ?.getString(AiBackend.PREFERENCE_KEY, null)

    override fun cancelGeneration() {
        currentGeneration?.cancel(true)
        currentGeneration = null

        backends.values.filterIsInstance<CancellableBackend>()
            .forEach { it.cancelStreaming() }
    }
}
