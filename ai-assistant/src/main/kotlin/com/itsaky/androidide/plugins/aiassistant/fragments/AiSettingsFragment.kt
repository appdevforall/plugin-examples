package com.itsaky.androidide.plugins.aiassistant.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.viewmodel.AiBackend
import com.itsaky.androidide.plugins.aiassistant.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.plugins.aiassistant.viewmodel.EngineState
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ModelLoadingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiSettingsFragment : DialogFragment() {

    companion object {
        /** FragmentResult key signalling the chat screen that settings were closed. */
        const val RESULT_SETTINGS_CLOSED = "ai_settings_closed"
    }

    private lateinit var viewModel: AiSettingsViewModel
    private lateinit var settingsToolbar: LinearLayout
    private lateinit var backButton: ImageButton
    private lateinit var backendSpinner: Spinner
    private lateinit var backendSpecificContainer: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disable Material transitions to avoid resource loading issues
        // Plugin uses compileOnly dependencies, so Material transition resources aren't bundled
        enterTransition = null
        exitTransition = null
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

                    val uriString = it.toString()
                    viewModel.loadModelFromUri(uriString, requireContext())
                    Toast.makeText(requireContext(), "Loading model...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    /**
     * Route inflation through the host so the dialog's views resolve against a Context whose
     * Configuration tracks the IDE's day/night setting (DayNight PluginTheme + values-night/
     * colors). Replaces the old cloneInContext(pluginContext), which pinned the UI to light mode.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return com.itsaky.androidide.plugins.base.PluginFragmentHelper.getPluginInflater(
            com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin.PLUGIN_ID, inflater
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeViewModel()
        initializeViews(view)
        setupToolbar()
        setupBackendSelector()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // This is a dialog, so the chat screen behind it never gets onResume when we close.
        // Signal it to re-resolve the selected backend (routing + availability + label).
        if (isAdded) {
            parentFragmentManager.setFragmentResult(RESULT_SETTINGS_CLOSED, Bundle.EMPTY)
        }
    }

    private fun initializeViewModel() {
        viewModel = ViewModelProvider(
            this,
            AiSettingsViewModelFactory { getPluginContext() }
        )[AiSettingsViewModel::class.java]
    }

    private fun getPluginContext(): PluginContext? {
        return com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin.getContext()
    }

    private fun initializeViews(view: View) {
        settingsToolbar = view.findViewById(R.id.settings_toolbar)
        backButton = view.findViewById(R.id.toolbar_back_button)
        backendSpinner = view.findViewById(R.id.backend_autocomplete)
        backendSpecificContainer = view.findViewById(R.id.backend_specific_settings_container)
    }

    private fun setupToolbar() {
        backButton.setOnClickListener {
            // Close the dialog
            dismiss()
        }
    }

    private fun setupBackendSelector() {
        val backends = viewModel.getAvailableBackends()
        val backendNames = backends.map { it.displayName }
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            backendNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        backendSpinner.adapter = adapter

        val currentBackend = viewModel.getCurrentBackend()
        backendSpinner.setSelection(backends.indexOf(currentBackend))
        updateBackendSpecificUi(currentBackend)

        backendSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedBackend = backends[position]
                viewModel.saveBackend(selectedBackend)
                updateBackendSpecificUi(selectedBackend)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateBackendSpecificUi(backend: AiBackend) {
        backendSpecificContainer.removeAllViews()

        // Reuse the fragment's theme-aware inflater (routed through getPluginInflater) so these
        // sub-layouts follow the IDE day/night theme like the rest of the dialog.
        when (backend) {
            AiBackend.LOCAL_LLM -> {
                val localLlmView = layoutInflater
                    .inflate(R.layout.layout_settings_local_llm, backendSpecificContainer, false)
                backendSpecificContainer.addView(localLlmView)
                setupLocalLlmUi(localLlmView)
            }
            AiBackend.GEMINI -> {
                val geminiApiView = layoutInflater
                    .inflate(R.layout.layout_settings_gemini_api, backendSpecificContainer, false)
                backendSpecificContainer.addView(geminiApiView)
                setupGeminiApiUi(geminiApiView)
            }
        }
    }

    private fun setupLocalLlmUi(view: View) {
        val modelPathTextView = view.findViewById<TextView>(R.id.selected_model_path)
        val browseButton = view.findViewById<Button>(R.id.btn_browse_model)
        val loadSavedButton = view.findViewById<Button>(R.id.loadSavedButton)
        val modelStatusTextView = view.findViewById<TextView>(R.id.model_status_text_view)
        val engineStatusTextView = view.findViewById<TextView>(R.id.engine_status_text)
        val simplePromptCheckbox = view.findViewById<CheckBox>(R.id.switch_simple_local_prompt)
        val shaInput = view.findViewById<EditText>(R.id.local_model_sha_input)

        browseButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        loadSavedButton.setOnClickListener {
            val savedPath = viewModel.savedModelPath.value
            if (savedPath != null) {
                viewModel.loadModelFromUri(savedPath, requireContext())
            }
        }

        shaInput?.apply {
            setText(viewModel.getLocalModelSha256().orEmpty())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    viewModel.saveLocalModelSha256(text?.toString())
                }
            }
        }

        simplePromptCheckbox?.apply {
            isChecked = viewModel.isUseSimpleLocalPromptEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                viewModel.setUseSimpleLocalPrompt(isChecked)
            }
        }

        // Observe engine state
        viewModel.engineState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is EngineState.Initializing, EngineState.Uninitialized -> {
                    engineStatusTextView.text = "Initializing engine..."
                    browseButton.isEnabled = false
                    loadSavedButton.isEnabled = false
                }
                is EngineState.Initialized -> {
                    engineStatusTextView.text = "Engine ready"
                    browseButton.isEnabled = true
                    loadSavedButton.isEnabled = viewModel.savedModelPath.value != null
                }
                is EngineState.Error -> {
                    engineStatusTextView.text = state.message
                    browseButton.isEnabled = false
                    loadSavedButton.isEnabled = false
                }
            }
        }

        // Observe saved model path
        viewModel.savedModelPath.observe(viewLifecycleOwner) { path ->
            loadSavedButton.isEnabled = path != null && viewModel.engineState.value is EngineState.Initialized

            if (path != null) {
                modelPathTextView.visibility = View.VISIBLE
                val fileName = viewModel.getSavedModelName() ?: viewModel.fallbackDisplayName(path)
                modelPathTextView.text = "Saved: $fileName"
            } else {
                modelPathTextView.visibility = View.GONE
            }
        }

        // Observe model loading state
        viewModel.modelLoadingState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ModelLoadingState.Idle -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "No model is currently loaded"
                }
                is ModelLoadingState.Loading -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "Loading model, please wait..."
                }
                is ModelLoadingState.Loaded -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "✅ Model loaded: ${state.modelName}"
                }
                is ModelLoadingState.Error -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = "❌ Error: ${state.message}"
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupGeminiApiUi(view: View) {
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.gemini_api_key_layout)
        val apiKeyInput = view.findViewById<EditText>(R.id.gemini_api_key_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.gemini_api_key_status_text)

        // Create model selection container
        val modelContainer = createModelSelectionUi(view)

        fun updateUiState(isEditing: Boolean) {
            if (isEditing) {
                statusTextView.visibility = View.GONE
                apiKeyLayout.visibility = View.VISIBLE
                saveButton.visibility = View.VISIBLE
                editButton.visibility = View.GONE
                clearButton.visibility = View.GONE
            } else {
                statusTextView.visibility = View.VISIBLE
                apiKeyLayout.visibility = View.GONE
                saveButton.visibility = View.GONE
                editButton.visibility = View.VISIBLE
                clearButton.visibility = View.VISIBLE
            }
        }

        val savedApiKey = viewModel.getGeminiApiKey()
        if (savedApiKey.isNullOrBlank()) {
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        } else {
            updateUiState(isEditing = false)
            val timestamp = viewModel.getGeminiApiKeySaveTimestamp()
            if (timestamp > 0) {
                val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                val savedDate = sdf.format(Date(timestamp))
                statusTextView.text = "API Key saved on: $savedDate"
            } else {
                statusTextView.text = "API Key is saved"
            }
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString()
            if (apiKey.isNotBlank()) {
                viewModel.saveGeminiApiKey(apiKey)
                Toast.makeText(requireContext(), "API Key saved", Toast.LENGTH_SHORT).show()

                updateUiState(isEditing = false)
                val timestamp = viewModel.getGeminiApiKeySaveTimestamp()
                val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
                val savedDate = sdf.format(Date(timestamp))
                statusTextView.text = "API Key saved on: $savedDate"
            } else {
                Toast.makeText(requireContext(), "API Key cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        editButton.setOnClickListener {
            updateUiState(isEditing = true)
            apiKeyInput.setText("••••••••••••••••")
            apiKeyInput.requestFocus()
        }

        clearButton.setOnClickListener {
            viewModel.clearGeminiApiKey()
            Toast.makeText(requireContext(), "API Key cleared", Toast.LENGTH_SHORT).show()
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        }

        // Setup model selection
        setupGeminiModelSelection(modelContainer)
    }

    private fun createModelSelectionUi(parent: View): LinearLayout {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
        }

        // Add title
        val titleText = TextView(context).apply {
            text = "Gemini Model"
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        container.addView(titleText)

        // Add current model display
        val currentModelText = TextView(context).apply {
            id = View.generateViewId()
            text = "Current: ${viewModel.getGeminiModel()}"
            setPadding(0, 0, 0, 8)
        }
        container.addView(currentModelText)

        // Add model spinner
        val modelSpinner = Spinner(context).apply {
            id = View.generateViewId()
        }
        container.addView(modelSpinner)

        // Add refresh button
        val refreshButton = Button(context).apply {
            id = View.generateViewId()
            text = "Refresh Models"
        }
        container.addView(refreshButton)

        // Find the parent container and add this
        if (parent is ViewGroup) {
            parent.addView(container)
        }

        // Tag the views for later reference
        container.tag = "model_container"
        currentModelText.tag = "current_model_text"
        modelSpinner.tag = "model_spinner"
        refreshButton.tag = "refresh_button"

        return container
    }

    private fun setupGeminiModelSelection(container: View) {
        val currentModelText = container.findViewWithTag<TextView>("current_model_text")
        val modelSpinner = container.findViewWithTag<Spinner>("model_spinner")
        val refreshButton = container.findViewWithTag<Button>("refresh_button")

        if (modelSpinner == null || refreshButton == null) return

        // Setup spinner
        fun updateModelSpinner(models: List<String>) {
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                models
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = adapter

            // Set current selection. If the saved model is no longer offered
            // migrate to the first available one so the next chat request
            // doesn't 404 on a dead model name.
            val currentModel = viewModel.getGeminiModel()
            val currentIndex = models.indexOf(currentModel)
            if (currentIndex >= 0) {
                modelSpinner.setSelection(currentIndex)
            } else if (models.isNotEmpty()) {
                modelSpinner.setSelection(0)
                val migrated = models[0]
                viewModel.saveGeminiModel(migrated)
                currentModelText?.text = "Current: $migrated"
            }
        }

        // Observe models
        viewModel.geminiModels.observe(viewLifecycleOwner) { models ->
            if (models.isNotEmpty()) {
                updateModelSpinner(models)
            }
        }

        // Observe loading state
        viewModel.geminiModelsLoading.observe(viewLifecycleOwner) { isLoading ->
            refreshButton.isEnabled = !isLoading
            refreshButton.text = if (isLoading) "Loading..." else "Refresh Models"
        }

        // Handle model selection
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = parent?.getItemAtPosition(position) as? String
                if (selectedModel != null && selectedModel != viewModel.getGeminiModel()) {
                    viewModel.saveGeminiModel(selectedModel)
                    currentModelText?.text = "Current: $selectedModel"
                    Toast.makeText(requireContext(), "Model changed to $selectedModel", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Handle refresh button
        refreshButton.setOnClickListener {
            viewModel.fetchGeminiModels()
        }

        // Initial fetch
        if (viewModel.geminiModels.value.isNullOrEmpty()) {
            viewModel.fetchGeminiModels()
        }
    }
}

/**
 * Factory for creating AiSettingsViewModel with PluginContext dependency.
 */
class AiSettingsViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiSettingsViewModel::class.java)) {
            return AiSettingsViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
