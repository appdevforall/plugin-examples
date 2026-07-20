package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
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
        val backend = backends[config.backendId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '${config.backendId}' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '${config.backendId}' is not available")
            )
        }

        val future = backend.generate(prompt, config)
        currentGeneration = future
        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        val backend = backends[config.backendId]
        if (backend == null) {
            callback.onError("Backend '${config.backendId}' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '${config.backendId}' is not available")
            return
        }

        backend.generateStreaming(prompt, config, callback)
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        val backend = backends[config.backendId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '${config.backendId}' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '${config.backendId}' is not available")
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
        val backend = backends[config.backendId]
        if (backend == null) {
            callback.onError("Backend '${config.backendId}' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '${config.backendId}' is not available")
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
            backend.generateStreaming(prompt, config, streamCallback)
            return
        }

        // Delegate to Gemini backend with tool support
        (backend as GeminiBackend).generateStreamingWithTools(prompt, history, config, tools, callback)
    }

    override fun getEmbeddings(text: String, backendId: String): CompletableFuture<FloatArray> {
        // Stub implementation - embeddings not needed for Phase 3
        return CompletableFuture.completedFuture(FloatArray(0))
    }

    override fun cancelGeneration() {
        currentGeneration?.cancel(true)
        currentGeneration = null

        backends.values.filterIsInstance<CancellableBackend>()
            .forEach { it.cancelStreaming() }
    }
}
