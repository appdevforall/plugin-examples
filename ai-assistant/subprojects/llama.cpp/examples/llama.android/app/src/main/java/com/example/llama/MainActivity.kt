package com.example.llama

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.StrictMode
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.llama.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity(
    activityManager: ActivityManager? = null,
    downloadManager: DownloadManager? = null,
    clipboardManager: ClipboardManager? = null,
) : AppCompatActivity() {
    private val tag: String? = this::class.simpleName

    private lateinit var binding: ActivityMainBinding
    private lateinit var messageAdapter: MessageAdapter

    private val activityManager by lazy { activityManager ?: getSystemService<ActivityManager>()!! }
    private val downloadManager by lazy { downloadManager ?: getSystemService<DownloadManager>()!! }
    private val clipboardManager by lazy {
        clipboardManager ?: getSystemService<ClipboardManager>()!!
    }

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(application)
    }
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var models: List<Downloadable>

    private fun availableMemory(): ActivityManager.MemoryInfo {
        return ActivityManager.MemoryInfo().also { memoryInfo ->
            activityManager.getMemoryInfo(memoryInfo)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.edit { putString(SAVED_MODEL_URI_KEY, uri.toString()) }
                viewModel.onNewModelSelected(uri)
                viewModel.loadModelFromUri(uri, this@MainActivity)
            }
        }

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .build()
        )

        val free = Formatter.formatFileSize(this, availableMemory().availMem)
        val total = Formatter.formatFileSize(this, availableMemory().totalMem)
//        viewModel.log("Current memory: $free / $total")
//        viewModel.log("Downloads directory: ${getExternalFilesDir(null)}")

        viewModel.checkInitialSavedModel(applicationContext)

        val extFilesDir = getExternalFilesDir(null)!!

        models = listOf(
            Downloadable(
                "Phi-2 7B (Q4_0, 1.6 GiB)",
                Uri.parse("https://huggingface.co/ggml-org/models/resolve/main/phi-2/ggml-model-q4_0.gguf?download=true"),
                File(extFilesDir, "phi-2-q4_0.gguf"),
            ),
            Downloadable(
                "TinyLlama 1.1B (f16, 2.2 GiB)",
                Uri.parse("https://huggingface.co/ggml-org/models/resolve/main/tinyllama-1.1b/ggml-model-f16.gguf?download=true"),
                File(extFilesDir, "tinyllama-1.1-f16.gguf"),
            ),
            Downloadable(
                "Phi 2 DPO (Q3_K_M, 1.48 GiB)",
                Uri.parse("https://huggingface.co/TheBloke/phi-2-dpo-GGUF/resolve/main/phi-2-dpo.Q3_K_M.gguf?download=true"),
                File(extFilesDir, "phi-2-dpo.Q3_K_M.gguf")
            ),
        )

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        messageAdapter = MessageAdapter(this)
        binding.messagesRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        binding.sendButton.setOnClickListener { send() }
        binding.benchButton.setOnClickListener { viewModel.bench(8, 4, 1) }
        binding.clearButton.setOnClickListener { viewModel.clear() }
        binding.copyButton.setOnClickListener {
            val textToCopy = viewModel.chatMessages.value
                ?.joinToString("\n") { it.text }
                .orEmpty()
            clipboardManager.setPrimaryClip(ClipData.newPlainText("conversation", textToCopy))
        }
        binding.loadFileButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
        binding.loadSavedButton.setOnClickListener { loadFromSaved() }

        binding.messageEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                return@setOnEditorActionListener true
            }
            false
        }

        binding.downloadableModelsContainer.removeAllViews()
        models.forEach { model ->
            val button = Button(this).apply {
                text = model.name
                setOnClickListener { viewModel.onDownloadableClicked(model, downloadManager) }
            }
            binding.downloadableModelsContainer.addView(button)
        }

        binding.streamingSwitch.isChecked = viewModel.isStreamingEnabled
        binding.streamingSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setStreaming(isChecked)
        }

        binding.toolUseSwitch.isChecked = viewModel.isToolUseEnabled
        binding.toolUseSwitch.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setToolUse(isChecked)
        }

        viewModel.initializeModelStates(models)
    }

    private fun observeViewModel() {
        // Use lifecycleScope to collect the StateFlow safely
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatMessages.collect { messages ->
                    messageAdapter.submitList(messages)
                    if (messages.isNotEmpty()) {
                        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }
        viewModel.modelStates.observe(this) { states ->
            models.forEachIndexed { index, model ->
                val button = binding.downloadableModelsContainer.getChildAt(index) as? Button
                val state = states[model.name]
                button?.let {
                    when (state) {
                        is DownloadUiState.Ready -> {
                            it.text = "Download ${model.name}"
                            it.isEnabled = true
                        }
                        is DownloadUiState.Downloading -> {
                            it.text = "Downloading ${state.progress}%"
                            it.isEnabled = false
                        }
                        is DownloadUiState.Downloaded -> {
                            it.text = "Load ${model.name}"
                            it.isEnabled = true
                        }
                        is DownloadUiState.Error -> {
                            it.text = "Error! Retry ${model.name}"
                            it.isEnabled = true
                        }
                        null -> {}
                    }
                }
            }
        }
        viewModel.savedModelUri.observe(this) { uri ->
            if (uri != null) {
                binding.loadSavedButton.isEnabled = true
                binding.savedModelPathTextView.visibility = View.VISIBLE
                binding.savedModelPathTextView.text = "Saved: ${getFileNameFromUri(uri)}"
            } else {
                binding.loadSavedButton.isEnabled = false
                binding.savedModelPathTextView.visibility = View.GONE
            }
        }
    }

    private fun send() {
        // The ViewModel property can be bound directly or updated like this
        viewModel.message = binding.messageEditText.text.toString()
        viewModel.send()
        binding.messageEditText.text.clear()
    }

    private fun loadFromSaved() {
        val savedUri = viewModel.savedModelUri.value
        if (savedUri != null) {
            val hasPermission = contentResolver.persistedUriPermissions.any {
                it.uri == savedUri && it.isReadPermission
            }
            if (hasPermission) {
                viewModel.loadModelFromUri(savedUri, this)
            } else {
//                viewModel.log("Permission for saved model lost. Please select it again.")
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    remove(SAVED_MODEL_URI_KEY)
                }
                viewModel.onNewModelSelected(null)
            }
        } else {
//            viewModel.log("No saved model found.")
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
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
        return result ?: "Unknown File"
    }
}
