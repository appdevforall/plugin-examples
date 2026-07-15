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

    /**
     * Get the model name from preferences, or use default.
     * Default to gemini-1.5-flash for compatibility, fallback to gemini-2.5-flash.
     */
    private fun getModelName(): String {
        val prefs = try {
            val aiAssistantContext = SharedServices.get(PluginContext::class.java)
            aiAssistantContext?.getPluginSharedPreferences("AgentSettings")
        } catch (e: Exception) {
            context.logger.error("GeminiBackend: Error getting preferences", e)
            null
        }
        return prefs?.getString("gemini_model", "gemini-1.5-flash") ?: "gemini-1.5-flash"
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
     * List available Gemini models.
     * Returns a CompletableFuture with list of model names.
     *
     * Note: The Gemini Java SDK 1.16.0 doesn't have a direct list models API,
     * so we return a curated list of commonly available models.
     */
    fun listModels(): CompletableFuture<List<String>> {
        val future = CompletableFuture<List<String>>()

        scope.launch {
            try {
                context.logger.info("GeminiBackend: Returning available models list")

                // Return list of known Gemini models
                // gemini-1.5-* may be deprecated, but included for fallback
                future.complete(listOf(
                    "gemini-1.5-flash",
                    "gemini-1.5-pro",
                    "gemini-2.5-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-2.5-pro",
                    "gemini-3-flash",
                    "gemini-3.5-flash"
                ))
            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error in listModels", e)
                // Return minimal fallback list on error
                future.complete(listOf(
                    "gemini-1.5-flash",
                    "gemini-2.5-flash",
                    "gemini-3.5-flash"
                ))
            }
        }

        return future
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
