package com.example.llama

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

const val SAVED_MODEL_URI_KEY = "saved_model_uri"
const val PREFS_NAME = "LlamaPrefs"

/**
 * Manages UI-related state and delegates business logic to the ChatRepository.
 * This ViewModel does not contain any complex logic itself; it's a bridge
 * between the UI and the data/domain layers.
 */
class MainViewModel(
    private val localLlmRepositoryImpl: LocalLlmRepositoryImpl,
) : ViewModel() {

    // --- State Exposure ---

    // Exposes the conversation history from the repository for the UI to observe.
    val chatMessages: StateFlow<List<ChatMessage>> = localLlmRepositoryImpl.messages
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    // UI state for downloadable models. This is pure UI logic, so it stays here.
    private val _modelStates = MutableLiveData<Map<String, DownloadUiState>>(emptyMap())
    val modelStates: LiveData<Map<String, DownloadUiState>> get() = _modelStates

    // UI state for the saved model path.
    private val _savedModelUri = MutableLiveData<Uri?>(null)
    val savedModelUri: LiveData<Uri?> get() = _savedModelUri

    // Simple UI state properties.
    var message: String = ""
    var isStreamingEnabled = true
        private set
    var isToolUseEnabled = true
        private set


    // --- UI Event Handlers ---

    fun send() {
        if (message.isBlank()) return
        val textToSend = message
        message = "" // Clear the input after sending

        viewModelScope.launch {
            localLlmRepositoryImpl.sendMessage(textToSend, isStreamingEnabled, isToolUseEnabled)
        }
    }

    fun loadModelFromUri(uri: Uri, context: Context) {
        viewModelScope.launch {
            // The logic to copy the file from URI to a cache location is a UI concern.
            val destinationFile = withContext(viewModelScope.coroutineContext) {
                val fileName = getFileName(context, uri)
                val dest = File(context.cacheDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(dest).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                dest
            }
            // Now, delegate the actual loading to the repository.
            localLlmRepositoryImpl.loadModel(destinationFile.absolutePath)
        }
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        viewModelScope.launch {
            localLlmRepositoryImpl.bench(pp, tg, pl, nr)
        }
    }

    fun clear() {
        localLlmRepositoryImpl.clear()
    }

    // --- State Updaters ---

    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun setStreaming(isEnabled: Boolean) {
        isStreamingEnabled = isEnabled
    }

    fun setToolUse(isEnabled: Boolean) {
        isToolUseEnabled = isEnabled
    }

    fun onNewModelSelected(uri: Uri?) {
        _savedModelUri.value = uri
    }

    // --- Download Management (UI-specific logic) ---

    fun initializeModelStates(models: List<Downloadable>) {
        val initialState = models.associate { model ->
            val state = if (model.destination.exists()) {
                DownloadUiState.Downloaded
            } else {
                DownloadUiState.Ready
            }
            model.name to state
        }
        _modelStates.value = initialState
    }

    fun onDownloadableClicked(item: Downloadable, dm: DownloadManager) {
        val currentState = _modelStates.value?.get(item.name)
        when (currentState) {
            is DownloadUiState.Downloaded -> {
                viewModelScope.launch {
                    localLlmRepositoryImpl.loadModel(item.destination.path)
                }
            }

            is DownloadUiState.Ready, is DownloadUiState.Error, null -> {
                startDownload(item, dm)
            }

            is DownloadUiState.Downloading -> { /* Already downloading, do nothing */
            }
        }
    }

    private fun startDownload(item: Downloadable, dm: DownloadManager) {
        if (item.destination.exists()) item.destination.delete()
        val request = DownloadManager.Request(item.source)
            .setTitle("Downloading ${item.name}")
            .setDestinationUri(Uri.fromFile(item.destination))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = dm.enqueue(request)

        viewModelScope.launch {
            monitorDownload(item, id, dm)
        }
    }

    private suspend fun monitorDownload(item: Downloadable, id: Long, dm: DownloadManager) {
        val query = DownloadManager.Query().setFilterById(id)
        while (true) {
            val cursor = dm.query(query)
            if (!cursor.moveToFirst()) {
                cursor.close()
                updateModelState(item.name, DownloadUiState.Error("Download cancelled or failed"))
                return
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    updateModelState(item.name, DownloadUiState.Downloaded)
                    cursor.close()
                    return
                }

                DownloadManager.STATUS_FAILED -> {
                    updateModelState(item.name, DownloadUiState.Error("Download failed"))
                    cursor.close()
                    return
                }

                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING -> {
                    val sofar =
                        cursor.getLongOrNull(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            ?: 0
                    val total =
                        cursor.getLongOrNull(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            ?: 1
                    val progress = ((sofar * 100.0) / total).toInt()
                    updateModelState(item.name, DownloadUiState.Downloading(progress))
                }
            }
            cursor.close()
            delay(1000L)
        }
    }

    private fun updateModelState(name: String, state: DownloadUiState) {
        val currentStates = _modelStates.value.orEmpty().toMutableMap()
        currentStates[name] = state
        _modelStates.postValue(currentStates)
    }

    // --- Saved Model URI Logic ---

    fun checkInitialSavedModel(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUriString = prefs.getString(SAVED_MODEL_URI_KEY, null)
        _savedModelUri.value = savedUriString?.toUri()
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIndex >= 0) {
                        result = cursor.getString(colIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result?.ifBlank { "temp_model.gguf" } ?: "temp_model.gguf"
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            localLlmRepositoryImpl.cleanup()
        }
    }
}
