package com.itsaky.androidide.plugins.codesuggestions

import android.util.Log
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.EditorContentChangeListener
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private const val TAG = "CodeSuggestionsPlugin"
private const val DEBOUNCE_MS = 800L

/**
 * Code Suggestions Plugin provides inline ghost-text completions.
 *
 * Listens to editor content changes, debounces 800ms, calls LLM for suggestions,
 * and displays ghost text via IdeEditorService.showInlineSuggestion().
 *
 * Features:
 * - LRU cache to avoid redundant LLM calls
 * - Debouncing to reduce LLM load while typing
 * - Language-aware suggestions (Kotlin, Java, Python, etc.)
 * - Graceful degradation when ai-core plugin not loaded
 */
class CodeSuggestionsPlugin : IPlugin {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var context: PluginContext
    private var editorService: IdeEditorService? = null
    private var suggestionProvider: SuggestionProvider? = null

    private var debounceJob: Job? = null

    private val contentChangeListener = EditorContentChangeListener { fileContent, cursorLine, cursorColumn, language ->
        onEditorContentChanged(fileContent, cursorLine, cursorColumn, language)
    }

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        Log.i(TAG, "CodeSuggestionsPlugin initialized")
        return true
    }

    override fun activate(): Boolean {
        Log.i(TAG, "CodeSuggestionsPlugin activating...")

        // The editor service is provided by the IDE core and must be present.
        editorService = context.services.get(IdeEditorService::class.java)
        if (editorService == null) {
            Log.w(TAG, "IdeEditorService not available - cannot display suggestions")
            return false
        }

        // The LLM service comes from the ai-core plugin, which may activate after
        // us. Don't fail activation if it isn't ready yet - resolve it lazily on
        // the first content change instead.
        tryResolveLlmService()

        // Register for content change events
        try {
            editorService!!.addContentChangeListener(contentChangeListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register content change listener", e)
            return false
        }

        Log.i(TAG, "CodeSuggestionsPlugin activated")
        return true
    }

    /** Lazily resolves the LLM service (from ai-core) and builds the provider. */
    private fun tryResolveLlmService(): Boolean {
        if (suggestionProvider != null) return true
        val svc = try {
            // ai-core publishes this service through SharedServices. Keep the
            // context registry fallback for IDE builds that bridge shared
            // services into PluginContext.services.
            SharedServices.get(LlmInferenceService::class.java)
                ?: context.services.get(LlmInferenceService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving LlmInferenceService", e)
            null
        }
        if (svc != null) {
            suggestionProvider = SuggestionProvider(svc)
            Log.i(TAG, "LlmInferenceService resolved - suggestions enabled")
            return true
        }
        Log.w(TAG, "LlmInferenceService not available yet - install/activate the AI Core plugin")
        return false
    }

    override fun deactivate(): Boolean {
        Log.i(TAG, "CodeSuggestionsPlugin deactivating")
        debounceJob?.cancel()
        try {
            editorService?.removeContentChangeListener(contentChangeListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing content listener", e)
        }
        return true
    }

    override fun dispose() {
        Log.i(TAG, "CodeSuggestionsPlugin disposed")
        suggestionProvider?.clearCache()
    }

    private fun onEditorContentChanged(
        fileContent: String,
        cursorLine: Int,
        cursorColumn: Int,
        language: String,
    ) {
        if (editorService == null) return
        // ai-core may have activated after us; try to bind the LLM service now.
        if (suggestionProvider == null && !tryResolveLlmService()) return

        // Extract the word/prefix being typed (most recent word)
        val prefix = extractPrefix(fileContent, cursorLine, cursorColumn)
        if (prefix.isEmpty() || prefix.length < 2) {
            // Too short to suggest
            editorService!!.dismissInlineSuggestion()
            return
        }

        // Debounce: cancel previous job and start new one
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)

            try {
                val suggestion = suggestionProvider!!.getSuggestion(
                    fileContent = fileContent,
                    cursorLine = cursorLine,
                    cursorColumn = cursorColumn,
                    language = language,
                    prefix = prefix
                )

                // A newer keystroke may have cancelled us while generating. Bail without
                // touching the editor so we don't dismiss the suggestion the newer job shows.
                ensureActive()

                if (suggestion.isNotEmpty()) {
                    editorService!!.showInlineSuggestion(suggestion)
                    Log.d(TAG, "Showing suggestion: '$suggestion'")
                } else {
                    editorService!!.dismissInlineSuggestion()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error generating suggestion", e)
                editorService!!.dismissInlineSuggestion()
            }
        }
    }

    private fun extractPrefix(fileContent: String, cursorLine: Int, cursorColumn: Int): String {
        return try {
            val lines = fileContent.split("\n")
            if (cursorLine < 0 || cursorLine >= lines.size) return ""

            val currentLine = lines[cursorLine]
            if (cursorColumn < 0 || cursorColumn > currentLine.length) return ""

            // Get text from line start to cursor, extract the word being typed
            val beforeCursor = currentLine.substring(0, cursorColumn)
            val trimmed = beforeCursor.trimStart()
            // Find last word boundary (space, symbol, etc.)
            val lastBoundary = trimmed.indexOfLast { !it.isLetterOrDigit() && it != '_' }
            val word = if (lastBoundary == -1) trimmed else trimmed.substring(lastBoundary + 1)
            word
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting prefix", e)
            ""
        }
    }
}
