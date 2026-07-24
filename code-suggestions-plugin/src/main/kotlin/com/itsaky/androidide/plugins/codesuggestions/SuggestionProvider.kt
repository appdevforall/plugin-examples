package com.itsaky.androidide.plugins.codesuggestions

import android.util.Log
import com.itsaky.androidide.plugins.services.LlmInferenceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "SuggestionProvider"

/**
 * Generates code suggestions using an LLM with LRU caching.
 * Avoids redundant LLM calls for identical context.
 */
class SuggestionProvider(private val llmService: LlmInferenceService) {

    // Synchronized: accessOrder=true means even get() mutates, and overlapping IO jobs share this.
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    /**
     * Generates a code suggestion for the given context.
     * Uses cache to avoid redundant LLM calls.
     *
     * @param fileContent Full content of the current file
     * @param cursorLine Line number where cursor is (0-indexed)
     * @param cursorColumn Column number where cursor is (0-indexed)
     * @param language Programming language (kotlin, java, etc.)
     * @param prefix Recently typed text (for context)
     * @return Suggested completion text or empty if no suggestion
     */
    suspend fun getSuggestion(
        fileContent: String,
        cursorLine: Int,
        cursorColumn: Int,
        language: String,
        prefix: String,
    ): String = withContext(Dispatchers.IO) {
        return@withContext try {
            // Build up-to-cursor context from the file, bounded to the last 500 chars.
            val lines = fileContent.split("\n")
            val contextBefore = buildString {
                for (i in 0 until cursorLine.coerceIn(0, lines.size)) {
                    append(lines[i]).append("\n")
                }
                if (cursorLine in lines.indices) {
                    val line = lines[cursorLine]
                    append(line.substring(0, cursorColumn.coerceIn(0, line.length)))
                }
            }.takeLast(500)

            // Key on the language + up-to-cursor context the completion depends on, not just the last word.
            val cacheKey = "$language|$contextBefore"
            cache[cacheKey]?.let {
                Log.d(TAG, "Cache hit for prefix '$prefix'")
                return@withContext it
            }

            val prompt = """
                You are a $language code completion assistant.
                Complete the following code. Return ONLY the completion text, no explanation.

                Context:
                $contextBefore

                Complete this:
            """.trimIndent()

            // AI Core routes to the user-selected backend; we don't pick one here.
            val config = LlmInferenceService.LlmConfig(AUTO_BACKEND_ID)
            // Cancellation-aware await so a superseding keystroke cancels the in-flight LLM call.
            val response = llmService.generateCompletion(prompt, config).await()
            if (response.success) {
                val suggestion = response.text.trim()
                    .takeWhile { !it.isWhitespace() || it == ' ' } // Take tokens until newline
                    .trim()

                if (suggestion.isNotEmpty()) {
                    cache[cacheKey] = suggestion
                    Log.d(TAG, "Generated suggestion for '$prefix': $suggestion")
                    suggestion
                } else {
                    ""
                }
            } else {
                Log.w(TAG, "LLM error: ${response.error}")
                ""
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error generating suggestion", e)
            ""
        }
    }

    fun clearCache() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }

    companion object {
        private const val MAX_CACHE_SIZE = 100

        /** Sentinel backend id: AI Core resolves the user-selected backend for us. */
        private const val AUTO_BACKEND_ID = "auto"
    }
}

/**
 * Suspends until this future completes, cancelling it if the coroutine is cancelled.
 * @receiver the future to await
 * @return the future's completed value
 */
private suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { value, error ->
            if (error == null) cont.resume(value) else cont.resumeWithException(error)
        }
        cont.invokeOnCancellation { cancel(true) }
    }
