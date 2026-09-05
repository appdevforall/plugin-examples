package com.codeonthego.markdownpreviewer.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Process-wide singleton store for the markdown preview state. Plugin fragments
 * don't share a ViewModelStore reliably across classloader and host-activity
 * boundaries, so the state lives here and every fragment observes the same
 * StateFlow. Survives fragment recreation, theme changes, and activity restarts;
 * only process death wipes it.
 */
object MarkdownPreviewViewModel {

    private const val TAG = "MarkdownPreviewer"
    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd", "mkdn")
    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

    sealed interface UiState {
        data object Empty : UiState
        data class Loading(val message: String) : UiState
        data class Loaded(
            val fileName: String,
            val content: String,
            val isMarkdown: Boolean,
            val showingSource: Boolean
        ) : UiState
        data class Error(val message: String) : UiState
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _uiState = MutableStateFlow<UiState>(UiState.Empty)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadFile(file: File) {
        if (!file.exists()) {
            _uiState.value = UiState.Error("File not found: ${file.name}")
            return
        }
        if (!file.canRead()) {
            _uiState.value = UiState.Error("Cannot read file: ${file.name}")
            return
        }
        if (!isSupported(file.name)) {
            _uiState.value = UiState.Error("Unsupported file type. Only Markdown and HTML files are allowed.")
            return
        }
        launchLoad(file.name) {
            val content = file.readText()
            val isMarkdown = MARKDOWN_EXTENSIONS.contains(file.extension.lowercase())
            content to isMarkdown
        }
    }

    fun loadUri(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        val displayName = resolveDisplayName(appContext, uri) ?: "file"
        if (!isSupported(displayName)) {
            _uiState.value = UiState.Error("Unsupported file type. Only Markdown and HTML files are allowed.")
            return
        }
        launchLoad(displayName) {
            val stream = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open URI: $uri")
            val content = stream.use { it.bufferedReader().readText() }
            val isMarkdown = inferIsMarkdown(displayName, content)
            content to isMarkdown
        }
    }

    private fun isSupported(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in MARKDOWN_EXTENSIONS || ext in HTML_EXTENSIONS
    }

    fun toggleSource() {
        val current = _uiState.value
        if (current is UiState.Loaded) {
            _uiState.value = current.copy(showingSource = !current.showingSource)
        }
    }

    fun reset() {
        loadJob?.cancel()
        _uiState.value = UiState.Empty
    }

    private fun launchLoad(fileName: String, block: suspend () -> Pair<String, Boolean>) {
        loadJob?.cancel()
        _uiState.value = UiState.Loading("Loading $fileName…")
        loadJob = scope.launch {
            try {
                val (content, isMarkdown) = withContext(Dispatchers.IO) { block() }
                _uiState.value = UiState.Loaded(
                    fileName = fileName,
                    content = content,
                    isMarkdown = isMarkdown,
                    showingSource = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $fileName", e)
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast("/")
        } catch (e: Exception) {
            uri.lastPathSegment?.substringAfterLast("/")
        }
    }

    private fun inferIsMarkdown(fileName: String, content: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext in MARKDOWN_EXTENSIONS) return true
        if (ext in HTML_EXTENSIONS) return false
        return content.trim().startsWith("#")
    }
}
