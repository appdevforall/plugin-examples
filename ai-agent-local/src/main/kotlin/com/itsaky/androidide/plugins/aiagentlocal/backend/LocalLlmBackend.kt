package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.app.ActivityManager
import android.content.Context
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.OpenableColumns
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.feedback.IncompatibleModelException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.ModelLoadException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.ModelNotConfiguredException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.UserActionableLlmException
import com.itsaky.androidide.plugins.aiagentlocal.feedback.UserFeedback
import com.itsaky.androidide.plugins.aiagentlocal.model.ContextSizePolicy
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufHeader
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufHeaderReader
import com.itsaky.androidide.plugins.aiagentlocal.model.GgufModelInspector
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadDiagnostics
import com.itsaky.androidide.plugins.aiagentlocal.model.ModelLoadMessages
import com.itsaky.androidide.plugins.aiagentlocal.preferences.LocalLlmPreferences
import com.itsaky.androidide.plugins.aiagentlocal.prompt.LocalSystemPrompt
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local LLM backend using llama-impl for on-device inference.
 * Wraps llama-impl APIs and implements LlmBackend interface.
 */
class LocalLlmBackend(
    private val context: PluginContext
) : HistoryCapableBackend, CancellableBackend, ConfigurableBackend {

    companion object {
        /**
         * [LlmConfig.extraParams] key for an optional GBNF grammar. The caller
         * owns it (keeping this backend free of any tool vocabulary); absent →
         * unconstrained sampling.
         */
        const val EXTRA_PARAM_GRAMMAR = "grammar"


        /**
         * Belt-and-braces guard: `<|im_end|>` is an EOG control token, so the native loop
         * normally stops on it by itself. This only matters if a model emits it as plain
         * text, in which case the native stop truncates before the match.
         */
        private val CHAT_STOP = listOf("<|im_end|>")
    }

    private val llamaLazy = lazy { LLamaAndroid.instance() }
    private val llama by llamaLazy
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Owns the teardown coroutine in [close]. Separate from [scope] because that one is cancelled
     * first (to stop in-flight generation) and so could not run the unload itself; a
     * [SupervisorJob] keeps the two teardown steps independent. [close] cancels this scope once
     * the work completes, so nothing outlives the plugin.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Single-flight guard serializing whole generations on the shared native context. */
    private val generationMutex = Mutex()

    @Volatile private var currentStreamingJob: Job? = null
    @Volatile private var currentGenerateJob: Job? = null

    /** Renders load diagnoses as user-facing text; keeps R.string lookups out of this engine. */
    private val loadMessages by lazy { ModelLoadMessages(context.androidContext) }

    @Volatile private var modelLoaded = false
    @Volatile private var currentModelPath: String? = null

    /** Ensures the background warm-up load is launched at most once. */
    private val warmUpStarted = AtomicBoolean(false)

    override fun getId(): String = "local"

    override fun getName(): String = "Local LLM"

    /**
     * Whether the user's current selection names this backend.
     *
     * Asked of the router, which owns the selection — this backend's own preferences say nothing
     * about which backend is active, and reading the router's would be the cross-plugin coupling
     * this store exists to remove.
     *
     * No selection, or no router yet, counts as selected: that was the default before a selection
     * was ever stored, and preparing a model the user goes on to pick costs only time.
     */
    private fun isSelectedBackend(): Boolean {
        val selected = try {
            SharedServices.get(LlmInferenceService::class.java)?.preferredBackendId
        } catch (e: Exception) {
            context.logger.warn("LocalLlmBackend: could not read the selected backend", e)
            null
        }
        return selected.isNullOrBlank() || selected == getId()
    }

    /**
     * Written for small on-device models; see [LocalSystemPrompt] for why the wording belongs here
     * rather than with the caller.
     *
     * Null when the user turned the short prompt off, which is what hands them back the caller's
     * own full tool-calling prompt — a larger model can follow it, and this backend can run one.
     */
    override fun getSystemPrompt(request: SystemPromptRequest): String? =
        if (LocalLlmPreferences.useSimplePrompt(context)) LocalSystemPrompt.build(request) else null

    /**
     * Near-greedy: tool arguments must be copied out of earlier tool output verbatim, and a small
     * model given room to sample invents paths instead.
     */
    override fun getDefaultTemperature(): Float = 0.15f

    /**
     * This backend draws its own settings, so the consumer needs no knowledge of `.gguf` files,
     * engine state or memory headroom.
     */
    override fun getSettingsFragmentClassName(): String =
        "com.itsaky.androidide.plugins.aiagentlocal.settings.LocalLlmSettingsFragment"

    override fun isAvailable(): Boolean {
        // Check if model is actually configured
        val prefs = LocalLlmPreferences.of(context)

        val configuredPath = prefs.getString(LocalLlmPreferences.KEY_MODEL_PATH, null)

        context.logger.debug("LocalLlmBackend.isAvailable() - configured path: $configuredPath, modelLoaded: $modelLoaded")

        // Chat-open hits this; start loading now so the first message isn't gated on a cold load.
        maybeWarmUp(configuredPath)

        // Available if model is loaded OR if a path is configured
        return modelLoaded || !configuredPath.isNullOrBlank()
    }

    /**
     * Preloads the configured model in the background, once, so the first generation
     * doesn't pay the cold-load cost. No-op unless this backend is the selected one — warming a
     * multi-gigabyte model for a user who picked a cloud backend would be pure waste.
     *
     * @param configuredPath the configured model path/URI, or null/blank if unset.
     */
    private fun maybeWarmUp(configuredPath: String?) {
        if (configuredPath.isNullOrBlank() || modelLoaded) return
        if (!isSelectedBackend()) return
        if (!warmUpStarted.compareAndSet(false, true)) return

        scope.launch {
            try {
                // Serialize with real generations so a mid-warm-up send just waits for this load.
                generationMutex.withLock { ensureModelLoaded(configuredPath!!) }
                context.logger.info("Local model warm-up complete")
            } catch (e: Exception) {
                // Stay silent (the real send surfaces config errors); allow a later retry.
                context.logger.warn("Local model warm-up failed: ${e.message}")
                warmUpStarted.set(false)
            }
        }
    }

    /**
     * Resolves the user-selected model reference to a real filesystem path the native
     * loader can `fopen`.
     *
     * - A plain path is returned as-is.
     * - A `content://` URI (what SAF `OpenDocument` returns, held with persistable read
     *   permission) is streamed into a private cache file and that path is returned.
     *
     * IMPORTANT: this loads *exactly* the file the user selected. It must never fall back
     * to "some other .gguf on disk" — doing so silently loads the wrong model (e.g. an
     * embedding model), which aborts native inference and takes the IDE down. See ADFA-4388.
     */
    private fun resolveContentUriToPath(uriString: String): String? {
        if (!uriString.startsWith("content://")) {
            return uriString // Already a real file path
        }

        val uri = Uri.parse(uriString)
        context.logger.info("Resolving selected model URI: $uri")

        val resolver = context.androidContext.contentResolver

        // Read the selected document's display name + size (used to key the cache copy).
        var displayName = "model.gguf"
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) displayName = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        } catch (e: Exception) {
            context.logger.warn("Could not query model metadata for $uri: ${e.message}")
        }

        // Deterministic cache path keyed by URI + size, so the same selection reuses the
        // same copy and a different selection can never collide with it.
        val modelsDir = File(context.androidContext.filesDir, "llm-models").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val cacheFile = File(modelsDir, "${kotlin.math.abs(uriString.hashCode())}_${size}_$safeName")

        // Reuse a complete prior copy.
        if (cacheFile.exists() && (size < 0 || cacheFile.length() == size)) {
            context.logger.info("Using cached model copy: ${cacheFile.absolutePath}")
            pruneOtherModels(modelsDir, cacheFile)
            return cacheFile.absolutePath
        }

        // Materialize the selected URI into the cache. Copy to a temp file then rename, so an
        // interrupted copy can't be mistaken for a complete model on the next launch.
        return try {
            context.logger.info("Copying selected model into app storage: $displayName ($size bytes)")
            val tmp = File(modelsDir, cacheFile.name + ".tmp")
            val copied = resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (copied == null) {
                context.logger.error("Could not open input stream for selected model $uri")
                tmp.delete()
                return null
            }
            if (size >= 0 && tmp.length() != size) {
                context.logger.error("Model copy incomplete: expected $size bytes, got ${tmp.length()}")
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(cacheFile)) {
                tmp.copyTo(cacheFile, overwrite = true)
                tmp.delete()
            }
            pruneOtherModels(modelsDir, cacheFile)
            context.logger.info("Model ready at ${cacheFile.absolutePath}")
            cacheFile.absolutePath
        } catch (e: Exception) {
            context.logger.error("Failed to copy selected model into app storage", e)
            null
        }
    }

    /**
     * Keeps only the active model copy in the cache dir. Model files are large, and we only
     * ever need the currently-selected one on disk. Deleting a file that native code has
     * already mmap'd is safe on Android — the mapping stays valid until the model is freed.
     */
    private fun pruneOtherModels(modelsDir: File, keep: File) {
        modelsDir.listFiles()?.forEach { f ->
            if (f.absolutePath != keep.absolutePath && f.delete()) {
                context.logger.debug("Pruned old model copy: ${f.name}")
            }
        }
    }

    /**
     * Loads [modelPath] unless it is already resident, diagnosing any native failure into a
     * [ModelLoadException]. Cancellation is rethrown first because [CancellationException] extends
     * [IllegalStateException] and would otherwise be diagnosed as a corrupt model.
     *
     * @param modelPath the configured model path or content URI
     */
    private suspend fun ensureModelLoaded(modelPath: String) {
        // Resolve content URI to actual file path
        val resolvedPath = resolveContentUriToPath(modelPath)
        if (resolvedPath == null) {
            throw ModelNotConfiguredException("Could not read the selected model file. Re-select the .gguf model in AI Settings.")
        }

        if (modelLoaded && currentModelPath == resolvedPath) {
            return // Already loaded
        }

        // One parse of the metadata block per load, feeding both the guard below and the context
        // sizing after the unload: it sits at the front of a multi-GB file, and a model switch
        // used to walk it twice.
        // Every stat is inside the block too: isFile and length() both hit the filesystem, which on
        // a removed SD card or a stale SAF mount blocks whoever called us.
        val openModel = { File(resolvedPath).takeIf { it.isFile }?.inputStream() }
        val (header, modelSizeBytes) = withContext(Dispatchers.IO) {
            GgufHeaderReader.read(openModel) to File(resolvedPath).length().takeIf { it > 0L }
        }

        // Guard the chat path against encoder-only embedding models. Running causal generation on
        // one aborts natively (SIGABRT) and takes the IDE down. Classify BEFORE unloading any
        // working chat model, so a wrong selection never tears down a good one. See ADFA-4388.
        // The overload rescans for the architecture alone, and only if the parse above gave up.
        val modelKind = withContext(Dispatchers.IO) {
            GgufModelInspector.classify(header, openModel)
        }
        if (modelKind.isEmbeddingOnly) {
            throw IncompatibleModelException(
                "The selected model is an embedding model and can't be used for chat. " +
                    "Choose a chat model in AI Settings."
            )
        }

        // Unload old model if loaded
        if (modelLoaded) {
            context.logger.info("Unloading previous model: $currentModelPath")
            llama.unload()
            modelLoaded = false
            currentModelPath = null
        }

        // Measured after the unload: availMem excludes the context and batch it just released.
        val availableBytes = availableMemoryBytes()
        ModelLoadDiagnostics.refuseBeforeLoad(availableBytes)?.let { shortfall ->
            throw ModelLoadException(loadMessages.describe(shortfall), shortfall)
        }

        val contextTokens = resolveContextSize(resolvedPath, availableBytes, header, modelSizeBytes)

        context.logger.info("Loading model: $resolvedPath")
        try {
            llama.load(resolvedPath, contextTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is UserActionableLlmException) throw e
            // Native load_model() signals failure only with a null handle, so diagnose the likely cause.
            context.logger.error("Native model load failed for $resolvedPath", e)
            val diagnosis = ModelLoadDiagnostics.diagnose(resolvedPath, availableMemoryBytes(), e.message)
            throw ModelLoadException(loadMessages.describe(diagnosis), diagnosis)
        }
        modelLoaded = true
        currentModelPath = resolvedPath
        context.logger.info("Model loaded successfully")
        reportEffectiveContextSize(contextTokens)
    }

    /**
     * Logs the context the native side actually created. It can be smaller than what was asked for
     * — `new_context` clamps a request above what the model was trained for — and without this the
     * only visible number is the request, so a prompt rejected as too long looks like it fit.
     *
     * @param requestedTokens the context [resolveContextSize] asked for
     */
    private suspend fun reportEffectiveContextSize(requestedTokens: Int) {
        val actual = try {
            llama.getContextSize()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.logger.warn("Could not read the created context size: ${e.message}")
            return
        }
        if (actual == requestedTokens) {
            context.logger.info("Context size in effect: $actual tokens")
        } else {
            context.logger.warn(
                "Context size in effect: $actual tokens, not the $requestedTokens requested;" +
                    " prompt-length limits follow the smaller number"
            )
        }
    }

    /**
     * Sizes the KV cache for this model on this device. Must run after any unload, so the freed
     * context is counted as available, and the answer is passed to [LLamaAndroid.load] rather than
     * stored anywhere. [ContextSizePolicy.choose] fails open, so this has no failure of its own.
     *
     * @param resolvedPath filesystem path to the model, already resolved from any content URI
     * @param availableBytes free RAM as [availableMemoryBytes] reports it, negative if unknown
     * @param header the model's metadata as read once by [ensureModelLoaded], null if unreadable
     * @param modelSizeBytes the model file's size, null if unreadable
     * @return the context size in tokens to load the model with
     */
    private fun resolveContextSize(
        resolvedPath: String,
        availableBytes: Long,
        header: GgufHeader?,
        modelSizeBytes: Long?,
    ): Int {
        val contextTokens = ContextSizePolicy.choose(
            header = header,
            availableBytes = availableBytes.takeIf { it >= 0L },
            modelSizeBytes = modelSizeBytes,
        )
        // Unconditional: a wrongly sized context otherwise just reads as the assistant forgetting.
        context.logger.info(
            "Context size for $resolvedPath: $contextTokens tokens" +
                " (model advertises ${header?.contextLength ?: "unknown"}," +
                " ${if (availableBytes >= 0L) "$availableBytes bytes free" else "free RAM unknown"})"
        )
        return contextTokens
    }

    /**
     * @return free RAM the OS reports, or -1 if unreadable (diagnosis then skips the low-memory case)
     */
    private fun availableMemoryBytes(): Long = try {
        val am = context.androidContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem
    } catch (e: Exception) {
        context.logger.warn("Could not read available memory: ${e.message}")
        -1L
    }

    /**
     * Wraps a turn in ChatML — the prompt format Qwen and most other on-device instruct GGUFs
     * were fine-tuned on.
     *
     * IMPORTANT: this must stay in sync with `formatChat = true` at the [LLamaAndroid.send] call
     * sites, which is what makes the native tokenizer parse `<|im_start|>`/`<|im_end|>` as control
     * tokens rather than literal text. Handed the bare `User:`/`Assistant:` scaffold this replaced,
     * a strictly-templated instruct model answers with `<|im_end|>` as its *first* token: the
     * native loop sees EOG immediately, generation ends before it starts, and the caller gets a
     * successful-but-empty response with no error to explain it.
     *
     * @param systemPrompt the system message, or null to omit the system turn
     * @param prompt the user message
     * @param history earlier turns, rendered as their own ChatML turns before [prompt]
     * @return the full ChatML prompt, ending in an open assistant turn for the model to continue
     */
    private fun buildPrompt(
        systemPrompt: String?,
        prompt: String,
        history: List<ChatMessage> = emptyList()
    ): String = buildString {
        if (systemPrompt != null) {
            append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        }
        for (msg in history) {
            val role = when (msg.role) {
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
                ChatMessage.Role.SYSTEM -> "system"
                // No native function calling here, so a tool result rides in as a user turn.
                ChatMessage.Role.TOOL -> "user"
            }
            append("<|im_start|>").append(role).append("\n").append(msg.content).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(prompt).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generate() called with prompt: ${prompt.take(50)}...")
        return runGeneration(buildPrompt(config.systemPrompt, prompt), config)
    }

    /**
     * Runs a non-streaming generation over an already-formatted prompt.
     *
     * Takes the finished prompt rather than a bare message so [generate] and
     * [generateWithHistory] share one path and the system turn is emitted exactly once.
     *
     * @param fullPrompt the complete prompt, as built by [buildPrompt]
     * @param config sampling settings for this request
     * @return a future completed with the response, or with a failure result on error
     */
    private fun runGeneration(fullPrompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        // Check if model is configured
        val prefs = LocalLlmPreferences.of(context)

        val configuredPath = prefs.getString(LocalLlmPreferences.KEY_MODEL_PATH, null)
        context.logger.info("LocalLlmBackend: configured model path = $configuredPath")

        if (configuredPath.isNullOrBlank()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("No model configured. Please go to Settings and select a .gguf model file.")
            )
        }

        val future = CompletableFuture<LlmResponse>()

        currentGenerateJob = scope.launch {
            try {
                // Serialize against other generations on the shared native context.
                generationMutex.withLock {
                    // Configure sampling (use defaults for topP and topK)
                    LLamaAndroid.configureSampling(
                        config.temperature,
                        0.9f,  // topP default
                        40     // topK default
                    )
                    LLamaAndroid.configureMaxTokens(config.maxTokens)

                    // Ensure model is loaded
                    ensureModelLoaded(configuredPath)

                    val startTime = System.currentTimeMillis()

                    // Collect all tokens
                    val responseBuilder = StringBuilder()
                    var tokenCount = 0

                    // Unconstrained path: clear any grammar left by a concurrent
                    // streaming call so completions never inherit a tool-call grammar.
                    llama.setGrammar(null)

                    llama.send(
                        message = fullPrompt,
                        formatChat = true,
                        stop = CHAT_STOP,
                        clearCache = false
                    ).collect { token ->
                        // Honor cancellation so Stop frees the run loop early.
                        ensureActive()
                        responseBuilder.append(token)
                        tokenCount++
                    }

                    val responseText = responseBuilder.toString()
                    context.logger.info("Generated response: ${responseText.take(50)}... ($tokenCount tokens)")

                    future.complete(LlmResponse.success(responseText, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (ce: CancellationException) {
                context.logger.info("Generation cancelled")
                future.completeExceptionally(ce)
                throw ce
            } catch (e: Exception) {
                context.logger.error("Error during generation", e)
                if (e is UserActionableLlmException) {
                    UserFeedback.notify(context.androidContext, e.message ?: "Local LLM is not configured.")
                }
                future.complete(LlmResponse.failure("Error: ${e.message}"))
            }
        }

        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        context.logger.info("LocalLlmBackend.generateStreaming() called")
        streamGeneration(buildPrompt(config.systemPrompt, prompt), config, callback)
    }

    /**
     * Streams a reply for a multi-turn conversation, rendering each earlier turn as its own
     * ChatML turn.
     *
     * @param history earlier turns, oldest first, excluding [prompt]
     * @param prompt the current user turn
     * @param config sampling settings; [LlmConfig.systemPrompt] becomes the leading system turn
     * @param callback receives tokens, completion, and errors
     */
    override fun generateStreamingWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        context.logger.info("LocalLlmBackend.generateStreamingWithHistory() called with ${history.size} messages")
        streamGeneration(buildPrompt(config.systemPrompt, prompt, history), config, callback)
    }

    /**
     * Streams a generation over an already-formatted prompt, serialized against every other
     * generation on the shared native context.
     *
     * @param fullPrompt the complete prompt, as built by [buildPrompt]
     * @param config sampling settings for this request
     * @param callback receives tokens, completion, and errors
     */
    private fun streamGeneration(fullPrompt: String, config: LlmConfig, callback: StreamCallback) {
        // Check if model is configured
        val prefs = LocalLlmPreferences.of(context)

        val configuredPath = prefs.getString(LocalLlmPreferences.KEY_MODEL_PATH, null)

        if (configuredPath.isNullOrBlank()) {
            callback.onError("No model configured. Please go to Settings and select a .gguf model file.")
            return
        }

        currentStreamingJob = scope.launch {
            try {
                // Hold the single-flight lock for the whole streaming generation.
                generationMutex.withLock {
                    try {
                        // Configure sampling (use defaults for topP and topK)
                        LLamaAndroid.configureSampling(
                            config.temperature,
                            0.9f,  // topP default
                            40     // topK default
                        )
                        LLamaAndroid.configureMaxTokens(config.maxTokens)

                        // Ensure model is loaded
                        ensureModelLoaded(configuredPath)

                        val startTime = System.currentTimeMillis()
                        var tokenCount = 0
                        val responseBuilder = StringBuilder()

                        // Apply the caller's grammar for this send() only; reset in the finally.
                        val grammar = config.extraParams?.get(EXTRA_PARAM_GRAMMAR) as? String
                        llama.setGrammar(grammar?.takeIf { it.isNotBlank() })

                        llama.send(
                            message = fullPrompt,
                            formatChat = true,
                            stop = CHAT_STOP,
                            // Keep the KV cache so the native layer reuses the common prefix (system prompt).
                            clearCache = false
                        ).collect { token ->
                            ensureActive()
                            callback.onToken(token)
                            responseBuilder.append(token)
                            tokenCount++
                        }

                        callback.onComplete(LlmResponse.success(responseBuilder.toString(), tokenCount, System.currentTimeMillis() - startTime))
                    } finally {
                        llama.setGrammar(null)
                    }
                }
            } catch (ce: CancellationException) {
                context.logger.info("Streaming generation cancelled")
                throw ce
            } catch (e: Exception) {
                context.logger.error("Error during streaming generation", e)
                if (e is UserActionableLlmException) {
                    UserFeedback.notify(context.androidContext, e.message ?: "Local LLM is not configured.")
                }
                callback.onError("Error: ${e.message}")
            }
        }
    }

    /**
     * Cancels any in-flight streaming or non-streaming generation (user pressed Stop),
     * cancelling the coroutine Job so the single-threaded run loop is freed early.
     */
    override fun cancelStreaming() {
        currentStreamingJob?.cancel()
        currentStreamingJob = null
        currentGenerateJob?.cancel()
        currentGenerateJob = null
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generateWithHistory() called with ${history.size} messages")
        return runGeneration(buildPrompt(config.systemPrompt, prompt, history), config)
    }

    /** Suspending model unload — safe to call from any coroutine. */
    private suspend fun unloadModelInternal() {
        if (modelLoaded) {
            llama.unload()
            modelLoaded = false
            currentModelPath = null
            context.logger.info("Model unloaded")
        }
    }

    /**
     * Release all resources. Called from [LocalLlmPlugin.dispose], which may run on
     * the main thread; llama.unload() drains a single-threaded native run loop and
     * can block while inference is in flight, so it must never run via runBlocking
     * on Main. Cancel generation, then unload on a background thread, then stop
     * the Llm-RunLoop thread so it doesn't outlive the plugin.
     *
     * The teardown runs in [cleanupScope] rather than a floating `CoroutineScope(...)`: the scope
     * is owned by this object and cancelled as soon as the work finishes, so there is no orphan
     * job left behind. It cannot be joined — dispose() may be on the main thread and unload()
     * blocks on the native run loop — so deterministic teardown is the strongest guarantee here.
     */
    fun close() {
        scope.cancel()
        val cleanup = cleanupScope.launch {
            if (!llamaLazy.isInitialized()) {
                return@launch
            }
            try {
                unloadModelInternal()
            } catch (t: Throwable) {
                context.logger.error("Error unloading model during close()", t)
            } finally {
                try {
                    llama.shutdown()
                } catch (t: Throwable) {
                    context.logger.error("Error shutting down Llm-RunLoop during close()", t)
                }
            }
        }
        cleanup.invokeOnCompletion { cleanupScope.cancel() }
    }
}
