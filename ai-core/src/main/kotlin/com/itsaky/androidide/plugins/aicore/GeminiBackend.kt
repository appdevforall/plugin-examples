package com.itsaky.androidide.plugins.aicore

import com.google.genai.Client
import com.google.genai.ResponseStream
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CompletableFuture

/**
 * Gemini API backend for cloud-based LLM inference.
 * Uses Google's Generative AI SDK to make API calls.
 */
class GeminiBackend(private val context: PluginContext) : LlmBackend {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var currentJob: Job? = null

    companion object {
        /** Current default model. gemini-1.5-* is retired on v1beta and now 404s. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** ListModels endpoint for the same API version the SDK uses for generateContent. */
        private const val LIST_MODELS_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"

        /** Only models advertising this generation method can be used for chat. */
        private const val METHOD_GENERATE_CONTENT = "generateContent"
    }

    /**
     * Get the model name from preferences, or use the current default.
     */
    private fun getModelName(): String {
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            context.logger.error("GeminiBackend: Error getting preferences", e)
            null
        }
        return prefs?.getString("gemini_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    /** Read the saved Gemini API key from ai-assistant's shared prefs, or null. */
    private fun readGeminiApiKey(): String? {
        val prefs = try {
            SharedServices.get(PluginContext::class.java)
                ?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            context.logger.error("GeminiBackend: Error getting preferences", e)
            null
        }
        return prefs?.getString("gemini_api_key", null)?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun getId(): String = "gemini"

    override fun getName(): String = "Gemini API"

    override fun isAvailable(): Boolean {
        // Check if API key is configured
        val prefs = try {
            // Get ai-assistant plugin's preferences
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            context.logger.error("GeminiBackend: Error getting preferences", e)
            null
        }

        val apiKey = prefs?.getString("gemini_api_key", null)
        context.logger.debug("GeminiBackend.isAvailable() - API key configured: ${!apiKey.isNullOrBlank()}")

        return !apiKey.isNullOrBlank()
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            try {
                val client = createClient()
                if (client == null) {
                    future.complete(LlmResponse.failure("Gemini API key not configured"))
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                context.logger.info("GeminiBackend: Generating response for prompt (${prompt.length} chars)")

                // Create history with user message
                val history = listOf(
                    Content.builder()
                        .parts(listOf(Part.builder().text(buildPrompt(prompt, config)).build()))
                        .role("user")
                        .build()
                )

                // Generate content
                val generateConfig = GenerateContentConfig.builder()
                    .temperature(config.temperature)
                    .maxOutputTokens(config.maxTokens)
                    .build()

                val response = client.models.generateContent(getModelName(), history, generateConfig)

                // Extract text from response (handle Java Optional)
                val candidates = response.candidates().orElse(emptyList())
                if (candidates.isEmpty()) {
                    future.complete(LlmResponse.failure("No response from Gemini API"))
                    return@launch
                }

                val content = candidates[0].content().orElse(null)
                val parts = content?.parts()?.orElse(emptyList())
                val text = parts?.firstOrNull()?.text()?.orElse(null)

                if (text.isNullOrBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size  // Approximate token count
                    context.logger.info("GeminiBackend: Generated ${text.length} chars, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }

            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error generating response", e)
                val errorMsg = formatErrorMessage(e)
                future.complete(LlmResponse.failure(errorMsg))
            }
        }

        return future
    }

    override fun generateStreaming(
        prompt: String,
        config: LlmConfig,
        callback: StreamCallback
    ) {
        currentJob = scope.launch {
            try {
                val client = createClient()
                if (client == null) {
                    callback.onError("Gemini API key not configured")
                    return@launch
                }

                val startTime = System.currentTimeMillis()
                context.logger.info("GeminiBackend: Streaming response for prompt (${prompt.length} chars)")

                // Create history with user message
                val history = listOf(
                    Content.builder()
                        .parts(listOf(Part.builder().text(buildPrompt(prompt, config)).build()))
                        .role("user")
                        .build()
                )

                // Generate content
                val generateConfig = GenerateContentConfig.builder()
                    .temperature(config.temperature)
                    .maxOutputTokens(config.maxTokens)
                    .build()

                // Use true streaming API
                val responseStream: ResponseStream<GenerateContentResponse> =
                    client.models.generateContentStream(getModelName(), history, generateConfig)

                val fullText = StringBuilder()
                var chunkCount = 0

                try {
                    // Iterate through stream chunks as they arrive
                    for (response in responseStream) {
                        val chunk = response.text()
                        if (chunk != null && chunk.isNotEmpty()) {
                            chunkCount++
                            fullText.append(chunk)
                            context.logger.debug("GeminiBackend: Stream chunk #$chunkCount: ${chunk.length} chars")

                            // Send each chunk to UI immediately
                            callback.onToken(chunk)
                        }
                    }

                    val finalText = fullText.toString()
                    if (finalText.isBlank()) {
                        callback.onError("Empty response from Gemini API")
                    } else {
                        val tokenCount = finalText.split("\\s+".toRegex()).size
                        context.logger.info("GeminiBackend: Streamed ${finalText.length} chars in $chunkCount chunks, ~$tokenCount tokens")
                        callback.onComplete(LlmResponse.success(finalText, tokenCount, System.currentTimeMillis() - startTime))
                    }
                } finally {
                    responseStream.close()
                }

            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error in streaming", e)
                callback.onError(formatErrorMessage(e))
            }
        }
    }

    override fun generateWithHistory(
        history: List<ChatMessage>,
        prompt: String,
        config: LlmConfig
    ): CompletableFuture<LlmResponse> {
        context.logger.info("GeminiBackend.generateWithHistory() called with ${history.size} messages")

        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            try {
                val client = createClient()
                if (client == null) {
                    future.complete(LlmResponse.failure("Gemini API key not configured"))
                    return@launch
                }

                val startTime = System.currentTimeMillis()

                // Convert chat history to Gemini format
                val geminiHistory = mutableListOf<Content>()

                // Add system message if provided
                if (config.systemPrompt != null) {
                    geminiHistory.add(
                        Content.builder()
                            .parts(listOf(Part.builder().text(config.systemPrompt).build()))
                            .role("user")
                            .build()
                    )
                    geminiHistory.add(
                        Content.builder()
                            .parts(listOf(Part.builder().text("Understood.").build()))
                            .role("model")
                            .build()
                    )
                }

                // Add chat history
                for (msg in history) {
                    val role = when (msg.role) {
                        ChatMessage.Role.USER -> "user"
                        ChatMessage.Role.ASSISTANT -> "model"
                        ChatMessage.Role.SYSTEM -> "user"  // System messages go as user
                    }
                    geminiHistory.add(
                        Content.builder()
                            .parts(listOf(Part.builder().text(msg.content).build()))
                            .role(role)
                            .build()
                    )
                }

                // Add current prompt
                geminiHistory.add(
                    Content.builder()
                        .parts(listOf(Part.builder().text(prompt).build()))
                        .role("user")
                        .build()
                )

                // Generate content
                val generateConfig = GenerateContentConfig.builder()
                    .temperature(config.temperature)
                    .maxOutputTokens(config.maxTokens)
                    .build()

                val response = client.models.generateContent(getModelName(), geminiHistory, generateConfig)

                // Extract text from response
                val candidates = response.candidates().orElse(emptyList())
                if (candidates.isEmpty()) {
                    future.complete(LlmResponse.failure("No response from Gemini API"))
                    return@launch
                }

                val content = candidates[0].content().orElse(null)
                val parts = content?.parts()?.orElse(emptyList())
                val text = parts?.firstOrNull()?.text()?.orElse(null)

                if (text.isNullOrBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size
                    context.logger.info("GeminiBackend: Generated ${text.length} chars with history, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }

            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error generating with history", e)
                future.complete(LlmResponse.failure(formatErrorMessage(e)))
            }
        }

        return future
    }

    /**
     * List the Gemini models currently available to the saved API key that support chat
     * (i.e. advertise the `generateContent` method). The result reflects the live v1beta
     * catalog, so any name returned here is safe to pass to [generate] / [generateStreaming]
     * without a 404 for a retired model.
     *
     * The Gemini Java SDK 1.16.0 has no list-models call, so this queries the REST
     * `ListModels` endpoint directly. Returns an empty list when no key is configured and
     * completes exceptionally on a network/API failure, so the caller can fall back to a
     * current-models-only list and never advertise a dead model.
     */
    fun listModels(): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()

        scope.launch {
            try {
                val key = readGeminiApiKey()
                if (key.isNullOrBlank()) {
                    context.logger.warn("GeminiBackend: no API key configured; cannot list live models")
                    future.complete(emptyList())
                    return@launch
                }
                val models = fetchAvailableModels(key)
                context.logger.info("GeminiBackend: ${models.size} models support $METHOD_GENERATE_CONTENT")
                future.complete(models)
            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error in listModels", e)
                future.completeExceptionally(e)
            }
        }

        return future
    }

    /**
     * Fetch and parse the ListModels catalog, following pagination, keeping only
     * models that support [METHOD_GENERATE_CONTENT] and stripping the `models/`
     * prefix from each name. Runs on the caller's (IO) coroutine.
     */
    private fun fetchAvailableModels(apiKey: String): List<String> {
        val encodedKey = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val names = mutableListOf<String>()
        var pageToken: String? = null

        do {
            val url = buildString {
                append(LIST_MODELS_URL)
                append("?key=").append(encodedKey)
                append("&pageSize=1000")
                pageToken?.let { append("&pageToken=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            }

            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            val body = try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw java.io.IOException("ListModels HTTP $code: $err")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val json = org.json.JSONObject(body)
            val models = json.optJSONArray("models")
            if (models != null) {
                for (i in 0 until models.length()) {
                    val model = models.getJSONObject(i)
                    val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                    val supportsChat = (0 until methods.length())
                        .any { methods.optString(it) == METHOD_GENERATE_CONTENT }
                    if (!supportsChat) continue
                    val name = model.optString("name").removePrefix("models/")
                    if (name.isNotBlank()) names.add(name)
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)

        return names.distinct()
    }

    /**
     * Create Gemini client with API key from settings.
     */
    private fun createClient(): Client? {
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            context.logger.error("GeminiBackend: Error getting preferences", e)
            return null
        }

        val apiKey = prefs?.getString("gemini_api_key", null)
        if (apiKey.isNullOrBlank()) {
            context.logger.error("GeminiBackend: API key not found")
            return null
        }

        return try {
            Client.builder()
                .apiKey(apiKey.trim())
                .build()
        } catch (e: Exception) {
            context.logger.error("GeminiBackend: Error creating client", e)
            null
        }
    }

    /**
     * Build the full prompt including system instructions.
     */
    private fun buildPrompt(userPrompt: String, config: LlmConfig): String {
        val systemPrompt = config.systemPrompt ?: "You are a helpful coding assistant."
        return """$systemPrompt

User: $userPrompt"""
    }

    /**
     * Generate streaming response with native Gemini function calling.
     * This method replaces text-based tool call parsing with structured function calling.
     */
    fun generateStreamingWithTools(
        prompt: String,
        history: List<ChatMessage>,
        config: LlmConfig,
        tools: List<LlmInferenceService.ToolDefinition>,
        callback: LlmInferenceService.ToolStreamCallback
    ) {
        currentJob = scope.launch {
            try {
                val client = createClient()
                if (client == null) {
                    callback.onError("Gemini API key not configured")
                    return@launch
                }

                context.logger.info("GeminiBackend: Streaming with tools - ${tools.size} tools available")

                // For Phase 1, we delegate to streaming without tools
                // Full function calling integration requires Gemini SDK extensions
                // This is a placeholder that uses the text-based approach
                // TODO: Full implementation in next iteration with proper FunctionDeclaration support

                val streamCallback = object : StreamCallback {
                    override fun onToken(token: String) = callback.onToken(token)
                    override fun onComplete(response: LlmResponse) = callback.onComplete(response)
                    override fun onError(error: String) = callback.onError(error)
                }

                generateStreaming(prompt, config, streamCallback)

            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error in streaming with tools", e)
                callback.onError(formatErrorMessage(e))
            }
        }
    }

    /**
     * Release all resources: cancel the backend scope and any in-flight
     * request. Called from AiCorePlugin.dispose().
     */
    fun close() {
        currentJob?.cancel()
        scope.cancel()
    }

    /**
     * Format error message with user-friendly descriptions.
     */
    private fun formatErrorMessage(e: Exception): String {
        return when {
            e.message?.contains("API key", ignoreCase = true) == true ->
                "Invalid API key. Please check your Gemini API key in settings."
            e.message?.contains("quota", ignoreCase = true) == true ||
            e.message?.contains("limit", ignoreCase = true) == true ->
                "API quota exceeded. Please check your Gemini API usage."
            e.message?.contains("network", ignoreCase = true) == true ->
                "Network error. Please check your internet connection."
            else -> "Gemini API error: ${e.message}"
        }
    }
}
