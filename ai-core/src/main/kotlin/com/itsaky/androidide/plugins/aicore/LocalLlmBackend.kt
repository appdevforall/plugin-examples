package com.itsaky.androidide.plugins.aicore

import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.OpenableColumns
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CompletableFuture

/**
 * Local LLM backend using llama-impl for on-device inference.
 * Wraps llama-impl APIs and implements LlmBackend interface.
 */
class LocalLlmBackend(private val context: PluginContext) : LlmBackend {

    private val llama = LLamaAndroid.instance()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile private var modelLoaded = false
    @Volatile private var currentModelPath: String? = null

    override fun getId(): String = "local"

    override fun getName(): String = "Local LLM"

    override fun isAvailable(): Boolean {
        // Check if model is actually configured
        val prefs = try {
            // Try to get ai-assistant plugin's preferences
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            null
        }

        val configuredPath = prefs?.getString("local_llm_model_path", null)

        context.logger.debug("LocalLlmBackend.isAvailable() - configured path: $configuredPath, modelLoaded: $modelLoaded")

        // Available if model is loaded OR if a path is configured
        return modelLoaded || !configuredPath.isNullOrBlank()
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

    private suspend fun ensureModelLoaded(modelPath: String) {
        // Resolve content URI to actual file path
        val resolvedPath = resolveContentUriToPath(modelPath)
        if (resolvedPath == null) {
            throw IllegalStateException("Could not read the selected model file. Re-select the .gguf model in AI Settings.")
        }

        if (modelLoaded && currentModelPath == resolvedPath) {
            return // Already loaded
        }

        // Unload old model if loaded
        if (modelLoaded) {
            context.logger.info("Unloading previous model: $currentModelPath")
            llama.unload()
            modelLoaded = false
            currentModelPath = null
        }

        // Load new model with resolved path
        context.logger.info("Loading model: $resolvedPath")
        llama.load(resolvedPath)
        modelLoaded = true
        currentModelPath = resolvedPath
        context.logger.info("Model loaded successfully")
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generate() called with prompt: ${prompt.take(50)}...")

        // Check if model is configured
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            null
        }

        val configuredPath = prefs?.getString("local_llm_model_path", null)
        context.logger.info("LocalLlmBackend: configured model path = $configuredPath")

        if (configuredPath.isNullOrBlank()) {
            return CompletableFuture.completedFuture(
                LlmResponse.failure("No model configured. Please go to Settings and select a .gguf model file.")
            )
        }

        val future = CompletableFuture<LlmResponse>()

        scope.launch {
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

                // Build full prompt with system message
                val fullPrompt = if (config.systemPrompt != null) {
                    "${config.systemPrompt}\n\nUser: $prompt\nAssistant:"
                } else {
                    "User: $prompt\nAssistant:"
                }

                val startTime = System.currentTimeMillis()

                // Collect all tokens
                val responseBuilder = StringBuilder()
                var tokenCount = 0

                llama.send(
                    message = fullPrompt,
                    formatChat = false,
                    stop = emptyList(),
                    clearCache = false
                ).collect { token ->
                    responseBuilder.append(token)
                    tokenCount++
                }

                val responseText = responseBuilder.toString()
                context.logger.info("Generated response: ${responseText.take(50)}... ($tokenCount tokens)")

                future.complete(LlmResponse.success(responseText, tokenCount, System.currentTimeMillis() - startTime))
            } catch (e: Exception) {
                context.logger.error("Error during generation", e)
                future.complete(LlmResponse.failure("Error: ${e.message}"))
            }
        }

        return future
    }

    override fun generateStreaming(prompt: String, config: LlmConfig, callback: StreamCallback) {
        context.logger.info("LocalLlmBackend.generateStreaming() called")

        // Check if model is configured
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            null
        }

        val configuredPath = prefs?.getString("local_llm_model_path", null)

        if (configuredPath.isNullOrBlank()) {
            callback.onError("No model configured. Please go to Settings and select a .gguf model file.")
            return
        }

        scope.launch {
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

                // Build full prompt with system message
                val fullPrompt = if (config.systemPrompt != null) {
                    "${config.systemPrompt}\n\nUser: $prompt\nAssistant:"
                } else {
                    "User: $prompt\nAssistant:"
                }

                val startTime = System.currentTimeMillis()
                var tokenCount = 0
                val responseBuilder = StringBuilder()

                llama.send(
                    message = fullPrompt,
                    formatChat = false,
                    stop = emptyList(),
                    clearCache = false
                ).collect { token ->
                    callback.onToken(token)
                    responseBuilder.append(token)
                    tokenCount++
                }

                callback.onComplete(LlmResponse.success(responseBuilder.toString(), tokenCount, System.currentTimeMillis() - startTime))
            } catch (e: Exception) {
                context.logger.error("Error during streaming generation", e)
                callback.onError("Error: ${e.message}")
            }
        }
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("LocalLlmBackend.generateWithHistory() called with ${history.size} messages")

        // Build conversation prompt
        val conversationBuilder = StringBuilder()

        if (config.systemPrompt != null) {
            conversationBuilder.append(config.systemPrompt).append("\n\n")
        }

        // Add history
        for (msg in history) {
            val role = when (msg.role) {
                ChatMessage.Role.USER -> "User"
                ChatMessage.Role.ASSISTANT -> "Assistant"
                ChatMessage.Role.SYSTEM -> "System"
            }
            conversationBuilder.append("$role: ${msg.content}\n")
        }

        // Add current prompt
        conversationBuilder.append("User: $prompt\nAssistant:")

        // Use regular generate with the full conversation
        return generate(conversationBuilder.toString(), config)
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
     * Release all resources. Called from AiCorePlugin.dispose(), which may run on
     * the main thread; llama.unload() drains a single-threaded native run loop and
     * can block while inference is in flight, so it must never run via runBlocking
     * on Main. Cancel generation, then unload on a background thread.
     */
    fun close() {
        scope.cancel()
        if (modelLoaded) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    unloadModelInternal()
                } catch (e: Exception) {
                    context.logger.error("Error unloading model during close()", e)
                }
            }
        }
    }
}
