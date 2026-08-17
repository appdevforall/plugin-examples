package com.itsaky.androidide.plugins.aicore.services

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aicore.backends.AiBackend
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of LlmInferenceService.
 *
 * Backend-agnostic: it knows no concrete backend type. Backends are contributed by separate plugins
 * (ai-agent-local, ai-agent-gemini, …) that call [registerBackend] on activation, and each optional
 * behaviour — [ToolCallingBackend], [HistoryCapableBackend], [CancellableBackend] — is declared by
 * the interface a backend implements, which this asks for by type before calling.
 *
 * @param logger the owning plugin's log, or null in unit tests
 */
class LlmInferenceServiceImpl(private val logger: PluginLogger? = null) : LlmInferenceService {

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
        val effectiveId = resolveAndStamp(config)
        val backend = backends[effectiveId]
            ?: return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' not found")
            )

        if (!backend.isAvailable()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("Backend '$effectiveId' is not available")
            )
        }

        val future = backend.generate(prompt, config)
        currentGeneration = future
        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        val effectiveId = resolveAndStamp(config)
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
        val effectiveId = resolveAndStamp(config)
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
        val effectiveId = resolveAndStamp(config)
        val backend = backends[effectiveId]
        if (backend == null) {
            callback.onError("Backend '$effectiveId' not found")
            return
        }

        if (!backend.isAvailable()) {
            callback.onError("Backend '$effectiveId' is not available")
            return
        }

        // Route by what the backend declares it can do, degrading one capability at a time.
        when (backend) {
            is ToolCallingBackend ->
                backend.generateStreamingWithTools(prompt, history, config, tools, callback)

            is HistoryCapableBackend ->
                backend.generateStreamingWithHistory(history, prompt, config, callback.asStream())

            else ->
                backend.generateStreaming(prompt, config, callback.asStream())
        }
    }

    /**
     * Adapts a tool callback to the plain streaming one, for a backend that reports no tool calls.
     *
     * @return a [StreamCallback] forwarding every event to this callback
     */
    private fun ToolStreamCallback.asStream(): StreamCallback = object : StreamCallback {
        override fun onToken(token: String) = this@asStream.onToken(token)
        override fun onComplete(response: LlmResponse) = this@asStream.onComplete(response)
        override fun onError(error: String) = this@asStream.onError(error)
    }

    override fun getEmbeddings(text: String, backendId: String): CompletableFuture<FloatArray> {
        // Stub implementation - embeddings not needed for Phase 3
        return CompletableFuture.completedFuture(FloatArray(0))
    }

    /**
     * Resolves where a request routes and writes that id back into [config], so every entry point
     * hands the backend the id it actually ran on rather than the [AiBackend.AUTO] sentinel.
     *
     * @param config the request's config; its `backendId` is replaced by the resolved id
     * @return the backend id to route to
     */
    private fun resolveAndStamp(config: LlmConfig): String {
        val effectiveId = effectiveBackendId(config.backendId)
        config.backendId = effectiveId
        return effectiveId
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
        val preferredId = AiBackend.idFromPreference(readSelectedBackendPreference())
        return backends[preferredId]?.takeIf { it.isAvailable() }?.getId()
            ?: backends.values.firstOrNull { it.isAvailable() }?.getId()
            ?: preferredId
    }

    /**
     * The backend the user selected on the Agent settings screen.
     *
     * Published so a backend can find out whether it is the active one without reading this
     * plugin's preferences — see [LlmInferenceService.getPreferredBackendId].
     *
     * @return the selected backend id, or null when nothing has been chosen yet
     */
    override fun getPreferredBackendId(): String? =
        readSelectedBackendPreference()?.let(AiBackend::idFromPreference)

    /**
     * Reads the raw stored selection from this plugin's own preferences.
     *
     * @return the stored preference value, or null when unset or before initialization
     */
    private fun readSelectedBackendPreference(): String? =
        AiCorePlugin.getContext()
            ?.getPluginSharedPreferences(AiBackend.PREFERENCE_FILE)
            ?.getString(AiBackend.PREFERENCE_KEY, null)

    override fun cancelGeneration() {
        currentGeneration?.cancel(true)
        currentGeneration = null

        // One backend refusing to cancel must not leave the others generating.
        backends.values.filterIsInstance<CancellableBackend>().forEach { backend ->
            try {
                backend.cancelStreaming()
            } catch (e: Throwable) {
                logger?.error("A backend failed to cancel its generation", e)
            }
        }
    }
}
