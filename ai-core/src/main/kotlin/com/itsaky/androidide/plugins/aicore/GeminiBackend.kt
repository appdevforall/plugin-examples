package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture

/**
 * Gemini API backend for cloud-based LLM inference.
 *
 * Talks to the Generative Language REST API directly over [HttpURLConnection] rather than the
 * google-genai SDK: the SDK bundles OkHttp 4.x and calls `RequestBody.create(String, MediaType)`,
 * but plugins run in the host IDE's classloader where `okhttp3` resolves to the host's older
 * OkHttp (no such overload) — that mismatch crashed generation with a NoSuchMethodError.
 * HttpURLConnection has no third-party dependency, so it works regardless of the host's OkHttp.
 */
class GeminiBackend(private val context: PluginContext) : LlmBackend {

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var currentJob: Job? = null

    companion object {
        /** Current default model. gemini-1.5-* is retired on v1beta and now 404s. */
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Base URL for the v1beta models API (ListModels, generateContent, streaming). */
        private const val MODELS_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"

        /** Generation method for chat; also the flag ListModels advertises for chat-capable models. */
        private const val METHOD_GENERATE_CONTENT = "generateContent"

        /** Server-sent-events streaming variant of [METHOD_GENERATE_CONTENT]. */
        private const val METHOD_STREAM_GENERATE_CONTENT = "streamGenerateContent"
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
        val stored = prefs?.getString("gemini_api_key", null)
        return SecureApiKeyStore.decrypt(stored)?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun getId(): String = "gemini"

    override fun getName(): String = "Gemini API"

    override fun isAvailable(): Boolean {
        // Available once a (decryptable) API key is configured.
        val apiKey = readGeminiApiKey()
        context.logger.debug("GeminiBackend.isAvailable() - API key configured: ${!apiKey.isNullOrBlank()}")
        return !apiKey.isNullOrBlank()
    }

    override fun generate(prompt: String, config: LlmConfig): CompletableFuture<LlmResponse> {
        val future = CompletableFuture<LlmResponse>()

        currentJob = scope.launch {
            try {
                val apiKey = readGeminiApiKey()
                    ?: run {
                        future.complete(LlmResponse.failure("Gemini API key not configured"))
                        return@launch
                    }

                val startTime = System.currentTimeMillis()
                context.logger.info("GeminiBackend: Generating response for prompt (${prompt.length} chars)")

                val contents = JSONArray().put(contentJson("user", buildPrompt(prompt, config)))
                val text = requestText(getModelName(), apiKey, buildRequestJson(contents, config))

                if (text.isBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size  // Approximate token count
                    context.logger.info("GeminiBackend: Generated ${text.length} chars, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
            } catch (e: Exception) {
                context.logger.error("GeminiBackend: Error generating response", e)
                future.complete(LlmResponse.failure(formatErrorMessage(e)))
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
                val apiKey = readGeminiApiKey()
                    ?: run {
                        callback.onError("Gemini API key not configured")
                        return@launch
                    }

                val startTime = System.currentTimeMillis()
                context.logger.info("GeminiBackend: Streaming response for prompt (${prompt.length} chars)")

                val contents = JSONArray().put(contentJson("user", buildPrompt(prompt, config)))
                val body = buildRequestJson(contents, config)

                val fullText = StringBuilder()
                var chunkCount = 0
                val conn = openConnection(getModelName(), METHOD_STREAM_GENERATE_CONTENT, sse = true, apiKey = apiKey)
                val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) conn.disconnect()
                }
                try {
                    writeBody(conn, body)
                    checkResponse(conn)
                    // SSE: each chunk arrives as a `data: {json}` line; parse text as it streams.
                    conn.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            ensureActive()
                            if (!line.startsWith("data:")) continue
                            val payload = line.substringAfter("data:").trim()
                            if (payload.isEmpty() || payload == "[DONE]") continue
                            // A malformed/non-JSON chunk must not abort the whole stream; skip it.
                            val chunk = runCatching { extractText(JSONObject(payload)) }.getOrElse {
                                context.logger.warn("GeminiBackend: skipping malformed SSE chunk: ${it.message}")
                                ""
                            }
                            if (chunk.isNotEmpty()) {
                                chunkCount++
                                fullText.append(chunk)
                                callback.onToken(chunk)
                            }
                        }
                    }
                } finally {
                    cancelHandle?.dispose()
                    conn.disconnect()
                }

                val finalText = fullText.toString()
                if (finalText.isBlank()) {
                    callback.onError("Empty response from Gemini API")
                } else {
                    val tokenCount = finalText.split("\\s+".toRegex()).size
                    context.logger.info("GeminiBackend: Streamed ${finalText.length} chars in $chunkCount chunks, ~$tokenCount tokens")
                    callback.onComplete(LlmResponse.success(finalText, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
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
                val apiKey = readGeminiApiKey()
                    ?: run {
                        future.complete(LlmResponse.failure("Gemini API key not configured"))
                        return@launch
                    }

                val startTime = System.currentTimeMillis()

                val contents = JSONArray()

                // Gemini has no system role; carry the system prompt as a leading user turn
                // acknowledged by the model, matching the SDK behavior this replaced.
                config.systemPrompt?.let { systemPrompt ->
                    contents.put(contentJson("user", systemPrompt))
                    contents.put(contentJson("model", "Understood."))
                }

                for (msg in history) {
                    val role = when (msg.role) {
                        ChatMessage.Role.USER -> "user"
                        ChatMessage.Role.ASSISTANT -> "model"
                        ChatMessage.Role.SYSTEM -> "user"  // System messages go as user
                    }
                    contents.put(contentJson(role, msg.content))
                }
                contents.put(contentJson("user", prompt))

                val text = requestText(getModelName(), apiKey, buildRequestJson(contents, config))

                if (text.isBlank()) {
                    future.complete(LlmResponse.failure("Empty response from Gemini API"))
                } else {
                    val tokenCount = text.split("\\s+".toRegex()).size
                    context.logger.info("GeminiBackend: Generated ${text.length} chars with history, ~$tokenCount tokens")
                    future.complete(LlmResponse.success(text, tokenCount, System.currentTimeMillis() - startTime))
                }
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
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
     * Returns an empty list when no key is configured and completes exceptionally on a
     * network/API failure, so the caller can fall back to a current-models-only list and
     * never advertise a dead model.
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
            } catch (e: CancellationException) {
                future.cancel(true)
                throw e
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
        val names = mutableListOf<String>()
        var pageToken: String? = null

        do {
            val url = buildString {
                append(MODELS_BASE_URL)
                append("?pageSize=1000")
                pageToken?.let { append("&pageToken=").append(java.net.URLEncoder.encode(it, "UTF-8")) }
            }

            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                // Pass the API key as a header, never in the URL query string: query
                // strings leak into logs, proxies, and crash reports.
                setRequestProperty("x-goog-api-key", apiKey)
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
                context.logger.info("GeminiBackend: Streaming with tools - ${tools.size} tools available")

                // For Phase 1, we delegate to streaming without tools
                // Full function calling integration requires structured FunctionDeclaration support
                // This is a placeholder that uses the text-based approach
                // TODO: Full implementation in next iteration with proper FunctionDeclaration support

                val streamCallback = object : StreamCallback {
                    override fun onToken(token: String) = callback.onToken(token)
                    override fun onComplete(response: LlmResponse) = callback.onComplete(response)
                    override fun onError(error: String) = callback.onError(error)
                }

                generateStreaming(prompt, config, streamCallback)

            } catch (e: CancellationException) {
                throw e
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
     * POST [body] to a model method and return the concatenated response text.
     *
     * @param model model name (without the `models/` prefix)
     * @param apiKey Gemini API key
     * @param body request payload built by [buildRequestJson]
     * @return the response text, or "" when the API returned no candidates/parts
     */
    private fun requestText(model: String, apiKey: String, body: JSONObject): String {
        val conn = openConnection(model, METHOD_GENERATE_CONTENT, sse = false, apiKey = apiKey)
        return try {
            writeBody(conn, body)
            checkResponse(conn)
            extractText(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Open a POST connection to `.../models/{model}:{method}`.
     *
     * @param sse when true, requests the server-sent-events stream (`?alt=sse`)
     * @return a configured, not-yet-written [HttpURLConnection]
     */
    private fun openConnection(
        model: String,
        method: String,
        sse: Boolean,
        apiKey: String,
    ): HttpURLConnection {
        val url = buildString {
            append(MODELS_BASE_URL).append('/').append(model).append(':').append(method)
            if (sse) append("?alt=sse")
        }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000  // generation can run for a while
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Header, not query string: keeps the key out of logs, proxies, and crash reports.
            setRequestProperty("x-goog-api-key", apiKey)
        }
    }

    /** Write [body] as the UTF-8 JSON request payload of [conn]. */
    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Throw an [IOException] carrying the API's error body on a non-2xx response, so
     * [formatErrorMessage] can turn it into a user-facing message.
     */
    private fun checkResponse(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IOException("Gemini HTTP $code: $err")
        }
    }

    /**
     * Build a generateContent request body.
     *
     * @param contents the `contents` array of role/parts turns
     * @param config supplies temperature and max output tokens
     * @return the request JSON
     */
    private fun buildRequestJson(contents: JSONArray, config: LlmConfig): JSONObject =
        JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", config.temperature.toDouble())
                    .put("maxOutputTokens", config.maxTokens)
            )

    /**
     * Build a single `contents` turn.
     *
     * @param role Gemini role, "user" or "model"
     * @param text the turn's text part
     * @return a `{role, parts:[{text}]}` object
     */
    private fun contentJson(role: String, text: String): JSONObject =
        JSONObject()
            .put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    /**
     * Extract and concatenate the text parts of the first candidate.
     *
     * @param response a generateContent response (or a single stream chunk)
     * @return the concatenated text, or "" when there are no candidates/parts
     */
    private fun extractText(response: JSONObject): String {
        val candidates = response.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }
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
