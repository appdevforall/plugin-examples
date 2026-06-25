package com.itsaky.androidide.plugins.aicore

import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.Part
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private val modelName = "gemini-2.0-flash-exp"

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
                    .temperature(config.temperature.toDouble())
                    .maxOutputTokens(config.maxTokens)
                    .build()

                val response = client.models.generateContent(modelName, history, generateConfig)

                // Extract text from response
                val candidates = response.candidates().getOrNull()
                if (candidates.isNullOrEmpty()) {
                    future.complete(LlmResponse.failure("No response from Gemini API"))
                    return@launch
                }

                val content = candidates[0].content().getOrNull()
                val parts = content?.parts()?.getOrNull()
                val text = parts?.firstOrNull()?.text()?.getOrNull()

                if (text.isNullOrBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    context.logger.info("GeminiBackend: Generated ${text.length} chars")
                    future.complete(LlmResponse.success(text))
                }

            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error generating response", e)
                val errorMsg = when {
                    e.message?.contains("API key") == true ->
                        "Invalid API key. Please check your Gemini API key in settings."
                    e.message?.contains("quota") == true || e.message?.contains("limit") == true ->
                        "API quota exceeded. Please check your Gemini API usage."
                    else -> "Gemini API error: ${e.message}"
                }
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

                context.logger.info("GeminiBackend: Streaming response for prompt (${prompt.length} chars)")

                // Create history with user message
                val history = listOf(
                    Content.builder()
                        .parts(listOf(Part.builder().text(buildPrompt(prompt, config)).build()))
                        .role("user")
                        .build()
                )

                // Generate streaming content
                val generateConfig = GenerateContentConfig.builder()
                    .temperature(config.temperature.toDouble())
                    .maxOutputTokens(config.maxTokens)
                    .build()

                val responseFlow = client.models.generateContentStream(modelName, history, generateConfig)
                val fullText = StringBuilder()

                responseFlow.collect { chunk ->
                    val candidates = chunk.candidates().getOrNull()
                    if (!candidates.isNullOrEmpty()) {
                        val content = candidates[0].content().getOrNull()
                        val parts = content?.parts()?.getOrNull()
                        val text = parts?.firstOrNull()?.text()?.getOrNull()

                        if (!text.isNullOrBlank()) {
                            fullText.append(text)
                            callback.onToken(text)
                        }
                    }
                }

                context.logger.info("GeminiBackend: Streamed ${fullText.length} chars total")
                callback.onComplete(LlmResponse.success(fullText.toString()))

            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error in streaming", e)
                val errorMsg = when {
                    e.message?.contains("API key") == true ->
                        "Invalid API key. Please check your Gemini API key in settings."
                    e.message?.contains("quota") == true || e.message?.contains("limit") == true ->
                        "API quota exceeded. Please check your Gemini API usage."
                    else -> "Gemini API error: ${e.message}"
                }
                callback.onError(errorMsg)
            }
        }
    }

    override fun cancelGeneration() {
        currentJob?.cancel()
        currentJob = null
        context.logger.info("GeminiBackend: Generation cancelled")
    }

    override fun getEmbeddings(text: String): CompletableFuture<FloatArray> {
        // Gemini embeddings not implemented yet
        val future = CompletableFuture<FloatArray>()
        future.completeExceptionally(UnsupportedOperationException("Embeddings not supported by Gemini backend"))
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

User: $userPrompt