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

            val prompt = buildString {
                append("Language: ").append(language).append('\n')
                append("Continue this code at the cursor. Output only the continuation.\n\n")
                append(contextBefore)
            }

            // AI Core routes to the user-selected backend; we don't pick one here.
            val config = LlmInferenceService.LlmConfig(AUTO_BACKEND_ID).apply {
                systemPrompt = COMPLETION_SYSTEM_PROMPT
                temperature = COMPLETION_TEMPERATURE
                maxTokens = MAX_COMPLETION_TOKENS
                stopSequences = listOf("\n\n", FENCE)
            }
            // Cancellation-aware await so a superseding keystroke cancels the in-flight LLM call.
            val response = llmService.generateCompletion(prompt, config).await()
            if (response.success) {
                val suggestion = sanitizeCompletion(response.text.orEmpty())

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

        private const val COMPLETION_TEMPERATURE = 0.2f

        /** Ghost text is one line; anything beyond this is the model starting to explain. */
        private const val MAX_COMPLETION_TOKENS = 64

        private const val FENCE = "```"

        private val COMPLETION_SYSTEM_PROMPT =
            "You are an inline code completion engine inside a code editor. " +
                "Reply with the raw code that continues the snippet at the cursor and " +
                "nothing else. Do not explain. Do not use markdown or code fences ($FENCE). " +
                "Do not name the language. Do not repeat the code you were given. " +
                "Reply with a single line of code."

        /** Bare fence infos ("java", "kotlin", ...) that are never a real completion. */
        private val LANGUAGE_TAGS = setOf(
            "java", "kotlin", "kt", "python", "py", "xml", "json", "gradle", "groovy",
            "javascript", "js", "typescript", "ts", "c", "cpp", "c++", "sh", "bash",
            "text", "plaintext", "code",
        )

        /**
         * Reduces a raw LLM reply to one line of inline-completion text.
         *
         * Chat-tuned models answer a completion request conversationally - a markdown fence,
         * sometimes a lead-in line, then the code - so taking the reply's first line verbatim
         * yields a "suggestion" of literally the opening fence plus its language info. Strip
         * the chat scaffolding first, then take the first line of what remains.
         *
         * @param raw the backend's response text
         * @return a single line of code, or empty when the reply held no usable completion
         */
        internal fun sanitizeCompletion(raw: String): String {
            var lines = raw.trim().lines()

            val fenceStart = lines.indexOfFirst { it.trimStart().startsWith(FENCE) }
            if (fenceStart >= 0) {
                val opener = stripLanguageInfo(
                    lines[fenceStart].trim().removeSurrounding(FENCE).removePrefix(FENCE).trim()
                )
                val body = lines.drop(fenceStart + 1)
                val fenceEnd = body.indexOfFirst { it.trimStart().startsWith(FENCE) }
                val inner = if (fenceEnd >= 0) body.take(fenceEnd) else body
                lines = if (opener.isNotEmpty()) listOf(opener) + inner else inner
            }

            return lines
                .firstOrNull { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() &&
                        !trimmed.startsWith(FENCE) &&
                        trimmed.lowercase() !in LANGUAGE_TAGS &&
                        !isPreamble(trimmed)
                }
                ?.trim()
                .orEmpty()
                .removeSuffix(FENCE)
                .trim()
                .removeSurrounding("`")
                .trim()
        }

        /**
         * Drops a fenced block's language info from its opening line, so `java foo(x)` (the
         * remains of ` ```java foo(x)``` `) yields just the code.
         *
         * @param fenceLine the opening fence line with its fence markers already removed
         * @return the line without a leading language tag, empty when that was all it held
         */
        private fun stripLanguageInfo(fenceLine: String): String =
            if (fenceLine.substringBefore(' ').lowercase() in LANGUAGE_TAGS) {
                fenceLine.substringAfter(' ', "").trim()
            } else {
                fenceLine
            }

        /**
         * Detects a natural-language lead-in such as "Here is the completion:".
         *
         * Only trailing-colon lines are candidates, since that is the shape a lead-in takes.
         * Requiring no code punctuation and several words keeps real block openers - Python's
         * `def f(x):`, `else:`, `class Foo:` - out of it.
         *
         * @param line a trimmed, non-empty line
         * @return true when the line reads as prose rather than code
         */
        private fun isPreamble(line: String): Boolean =
            line.endsWith(":") &&
                line.none { it in "(){}[];=<>" } &&
                line.count { it == ' ' } >= 2
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
