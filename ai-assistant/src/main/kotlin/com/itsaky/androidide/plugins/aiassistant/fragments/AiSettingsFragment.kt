package com.itsaky.androidide.plugins.aiassistant.fragments

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.gemini.GeminiKeyOnboarding
import com.itsaky.androidide.plugins.aiassistant.gemini.KeyVerification
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.aiassistant.viewmodel.AiBackend
import com.itsaky.androidide.plugins.aiassistant.viewmodel.AiSettingsViewModel
import com.itsaky.androidide.plugins.aiassistant.viewmodel.EngineState
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ModelLoadingState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    private var tooltipService: IdeTooltipService? = null

    /**
     * Set while the Gemini pane is on screen, so [onResume] can nudge the user towards **Paste
     * key** after they come back from AI Studio. Cleared when the pane is replaced or the view is
     * destroyed — it captures views, so holding it any longer would leak them.
     */
    private var onGeminiPaneResume: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disable Material transitions to avoid resource loading issues
        // Plugin uses compileOnly dependencies, so Material transition resources aren't bundled
        enterTransition = null
        exitTransition = null

        // Resolve the IDE tooltip service so the settings controls can offer in-app help.
        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(AiAssistantPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            AiAssistantPlugin.getContext()?.logger
                ?.warn("AiSettingsFragment: tooltip service unavailable", e)
        }
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide button). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, AiAssistantPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(it, takeFlags)

                    val uriString = it.toString()
                    viewModel.loadModelFromUri(uriString, requireContext())
                    Toast.makeText(requireContext(), getString(R.string.model_loading_toast), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.state_error, e.message), Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        onGeminiPaneResume?.invoke()
    }

    override fun onDestroyView() {
        // Drops the captured Gemini pane views along with the callback.
        onGeminiPaneResume = null
        super.onDestroyView()
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
        wireTooltip(backButton, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_BACK)
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

        wireTooltip(backendSpinner, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_BACKEND)

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
        // The Gemini pane's views are about to go; its resume callback must not outlive them.
        onGeminiPaneResume = null

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
        wireTooltip(browseButton, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_LOCAL_MODEL)

        loadSavedButton.setOnClickListener {
            val savedPath = viewModel.savedModelPath.value
            if (savedPath != null) {
                viewModel.loadModelFromUri(savedPath, requireContext())
            }
        }
        // Same concept as Browse — choosing which local model to run.
        wireTooltip(loadSavedButton, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_LOCAL_MODEL)

        shaInput?.apply {
            setText(viewModel.getLocalModelSha256().orEmpty())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    viewModel.saveLocalModelSha256(text?.toString())
                }
            }
        }
        // On the labelled wrapper, not the field: long-press there is the paste menu.
        view.findViewById<View>(R.id.local_model_sha_layout)
            ?.let { wireTooltip(it, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_LOCAL_SHA) }

        simplePromptCheckbox?.apply {
            isChecked = viewModel.isUseSimpleLocalPromptEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                viewModel.setUseSimpleLocalPrompt(isChecked)
            }
            wireTooltip(this, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT)
        }

        // Observe engine state
        viewModel.engineState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is EngineState.Initializing, EngineState.Uninitialized -> {
                    engineStatusTextView.text = getString(R.string.engine_initializing)
                    browseButton.isEnabled = false
                    loadSavedButton.isEnabled = false
                }
                is EngineState.Initialized -> {
                    engineStatusTextView.text = getString(R.string.engine_ready)
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
                modelPathTextView.text = getString(R.string.model_saved_path, fileName)
            } else {
                modelPathTextView.visibility = View.GONE
            }
        }

        // Observe model loading state
        viewModel.modelLoadingState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ModelLoadingState.Idle -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = getString(R.string.model_none_loaded)
                }
                is ModelLoadingState.Loading -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = getString(R.string.model_loading_wait)
                }
                is ModelLoadingState.Loaded -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = getString(R.string.model_loaded, state.modelName)
                }
                is ModelLoadingState.Error -> {
                    modelStatusTextView.visibility = View.VISIBLE
                    modelStatusTextView.text = getString(R.string.model_load_error, state.message)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupGeminiApiUi(view: View) {
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.gemini_api_key_layout)
        val apiKeyInput = view.findViewById<EditText>(R.id.gemini_api_key_input)
        val toggleVisibilityButton = view.findViewById<ImageButton>(R.id.btn_toggle_api_key_visibility)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.gemini_api_key_status_text)
        val getKeyButton = view.findViewById<Button>(R.id.btn_get_free_key)
        val verificationText = view.findViewById<TextView>(R.id.gemini_key_verification_text)

        // Not on apiKeyInput: long-press there is the paste menu, and a key is pasted.
        listOf(
            toggleVisibilityButton, saveButton, editButton, clearButton, statusTextView,
            verificationText
        ).forEach { wireTooltip(it, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_KEY) }
        wireTooltip(getKeyButton, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_GET_KEY)

        // Create model selection container
        val modelContainer = createModelSelectionUi(view)

        /**
         * Show the outcome of (or progress of) the live key check.
         *
         * @param message the user-facing line; carries no status glyph of its own
         * @param icon leading status drawable, or 0 for the states that don't warrant one
         *   (in-progress, and the hints shown on returning from AI Studio)
         */
        fun showVerification(message: String, @DrawableRes icon: Int = 0) {
            verificationText.text = message
            // Relative (not left/right) so the icon follows the layout direction in RTL locales.
            verificationText.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            verificationText.visibility = View.VISIBLE
        }

        /** Drop a verdict that no longer describes what is in the field. */
        fun hideVerification() {
            verificationText.visibility = View.GONE
            verificationText.text = ""
            verificationText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }

        // "Get API Key" is absent here on purpose: it stays visible while Gemini is selected.
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

        viewLifecycleOwner.lifecycleScope.launch {
            val savedApiKey = viewModel.getGeminiApiKey()
            val hasKey = !savedApiKey.isNullOrBlank()
            updateUiState(isEditing = !hasKey)
            if (hasKey) {
                statusTextView.text = savedApiKeyStatusText()
            } else {
                apiKeyInput.setText("")
                // A stored-but-undecryptable key also reads as null; warn as the Edit path does.
                if (viewModel.hasStoredGeminiApiKey()) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_unreadable),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        toggleVisibilityButton.setColorFilter(apiKeyInput.currentHintTextColor)

        var isKeyVisible = false

        fun applyKeyVisibility() {
            apiKeyInput.transformationMethod = if (isKeyVisible) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            toggleVisibilityButton.setImageResource(
                if (isKeyVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
            toggleVisibilityButton.contentDescription = getString(
                if (isKeyVisible) R.string.cd_hide_api_key else R.string.cd_show_api_key
            )
            toggleVisibilityButton.setColorFilter(apiKeyInput.currentHintTextColor)
            apiKeyInput.setSelection(apiKeyInput.text?.length ?: 0)
            setSecureWindow(isKeyVisible)
        }

        applyKeyVisibility()

        toggleVisibilityButton.setOnClickListener {
            isKeyVisible = !isKeyVisible
            applyKeyVisibility()
        }

        getKeyButton.setOnClickListener { openAiStudio() }

        // Coming back from AI Studio, point at the next step; the clipboard is never read.
        onGeminiPaneResume = {
            // Kept on the ViewModel so a rotation while AI Studio is in front doesn't lose the hint.
            if (viewModel.sentUserToAiStudio) {
                viewModel.sentUserToAiStudio = false
                // With a key already stored the field is hidden, so the next tap is Edit.
                showVerification(
                    if (apiKeyLayout.visibility == View.VISIBLE) {
                        getString(R.string.msg_key_hint_paste_into_field)
                    } else {
                        getString(R.string.msg_key_hint_edit_first)
                    }
                )
            }
        }

        /** Enable or disable everything that would race the in-flight key check. */
        fun setKeyEntryEnabled(enabled: Boolean) {
            saveButton.isEnabled = enabled
            getKeyButton.isEnabled = enabled
            apiKeyInput.isEnabled = enabled
        }

        /**
         * Encrypt and store [apiKey], then reflect the outcome. Only ever reached for a key Google
         * confirmed, or one the user chose to keep after an inconclusive check.
         */
        suspend fun persistKey(
            apiKey: String,
            verified: Boolean,
            resultText: String,
            @DrawableRes resultIcon: Int
        ) {
            if (!viewModel.saveGeminiApiKey(apiKey, verified)) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.msg_api_key_save_failed),
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_api_key_saved),
                Toast.LENGTH_SHORT
            ).show()
            updateUiState(isEditing = false)
            statusTextView.text = savedApiKeyStatusText()
            showVerification(resultText, resultIcon)
            // A different key can reach a different set of models, so the picker is re-fetched.
            viewModel.fetchGeminiModels()
        }

        /**
         * Offer to keep a key that could not be checked. Distinct from a rejection: refusing a good
         * key because the device is offline would leave the plugin unconfigurable, so this gets the
         * muted "unchecked" icon and a key Google actually refused never reaches here.
         */
        fun confirmSaveUnverified(apiKey: String, reason: String) {
            showVerification(reason, R.drawable.ic_key_unchecked)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.title_save_unverified_key)
                .setMessage(getString(R.string.msg_save_unverified_key, reason))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save_anyway) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        persistKey(
                            apiKey,
                            verified = false,
                            resultText = reason,
                            resultIcon = R.drawable.ic_key_unchecked
                        )
                    }
                }
                .show()
        }

        saveButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            // Blankness is the only shape rule: AI Studio keys need not match the AIza… form.
            if (apiKey.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.msg_api_key_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setKeyEntryEnabled(false)
            showVerification(getString(R.string.msg_verifying_key))
            viewLifecycleOwner.lifecycleScope.launch {
                val verdict = try {
                    viewModel.verifyGeminiKey(apiKey)
                } finally {
                    setKeyEntryEnabled(true)
                }
                when (verdict) {
                    // Model count omitted: the user saved a key, not asked for a catalog.
                    is KeyVerification.Verified -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // A rate-limited key is a working key, so it gets the same icon as a clean pass.
                    KeyVerification.RateLimited -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified_rate_limited),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // Nothing is written: a definitive refusal would only resurface mid-chat.
                    KeyVerification.Rejected -> {
                        showVerification(
                            getString(R.string.msg_key_rejected),
                            R.drawable.ic_key_rejected
                        )
                        apiKeyInput.requestFocus()
                    }

                    KeyVerification.Unreachable ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_key_unreachable))

                    KeyVerification.Unknown ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_key_uncheckable))
                }
            }
        }

        // Reveal the (already-fetched) key in an editable, focused field.
        fun revealEditMode(apiKey: String) {
            apiKeyInput.setText(apiKey)
            apiKeyInput.setSelection(apiKey.length)
            // The old verdict described the stored key, which is about to change.
            hideVerification()
            updateUiState(isEditing = true)
            isKeyVisible = false
            applyKeyVisibility()
            apiKeyInput.requestFocus()
        }

        editButton.setOnClickListener {
            editButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val apiKey = try {
                    viewModel.getGeminiApiKey()
                } finally {
                    editButton.isEnabled = true
                }
                // null = a key IS stored but won't decrypt; an empty box alone looks like data loss.
                if (apiKey == null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_unreadable),
                        Toast.LENGTH_LONG
                    ).show()
                }
                revealEditMode(apiKey.orEmpty())
            }
        }

        clearButton.setOnClickListener {
            viewModel.clearGeminiApiKey()
            Toast.makeText(requireContext(), getString(R.string.msg_api_key_cleared), Toast.LENGTH_SHORT).show()
            hideVerification()
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        }

        // Setup model selection
        setupGeminiModelSelection(modelContainer)
    }

    /**
     * Add or clear [WindowManager.LayoutParams.FLAG_SECURE] on this dialog's window.
     *
     * Set while the key is in clear text, or screenshots and the recents thumbnail would capture
     * it. A null window on the first call is safe: the initial state is masked.
     *
     * @param secure true to block capture, false to allow it again
     */
    private fun setSecureWindow(secure: Boolean) {
        val window = dialog?.window ?: return
        if (secure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Status line for a stored key: dated when the save time is known, generic otherwise, and
     * saying "verified" only for a key Google actually confirmed — a key kept through the
     * save-anyway path was never checked and must not claim otherwise.
     */
    private fun savedApiKeyStatusText(): String {
        val timestamp = viewModel.getGeminiApiKeySaveTimestamp()
        val verified = viewModel.isGeminiKeyVerified()
        if (timestamp <= 0) return getString(R.string.msg_api_key_is_saved)
        val savedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        return if (verified) {
            getString(R.string.msg_api_key_verified_on, savedDate)
        } else {
            getString(R.string.msg_api_key_saved_on, savedDate)
        }
    }

    /**
     * Open Google AI Studio's key page in the *system* browser.
     *
     * A real browser, not a WebView: Google blocks sign-in in embedded WebViews, and the user
     * should see Google's own URL bar. With no browser at all, the URL is copied instead.
     */
    private fun openAiStudio() {
        val url = GeminiKeyOnboarding.AI_STUDIO_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onSuccess { viewModel.sentUserToAiStudio = true }
            .onFailure { error ->
                AiAssistantPlugin.getContext()?.logger
                    ?.warn("AiSettingsFragment: no browser could open AI Studio", error)
                val message = if (copyToClipboard(url)) {
                    R.string.msg_no_browser_for_key
                } else {
                    R.string.msg_key_link_copy_failed
                }
                Toast.makeText(requireContext(), getString(message, url), Toast.LENGTH_LONG).show()
            }
    }

    /**
     * Put [text] on the clipboard.
     *
     * Only ever used for the public AI Studio URL — never for a key, which would put the secret
     * somewhere every app on the device can read it.
     *
     * @return true when the clipboard accepted the value
     */
    private fun copyToClipboard(text: String): Boolean {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        }.isSuccess
    }

    /**
     * Density-independent [dp] as whole pixels, for the views this screen builds in code. The
     * `setPadding` family takes raw pixels, so a literal shrinks as screen density rises.
     *
     * @param dp the density-independent size to convert
     * @return the equivalent size in device pixels
     */
    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun createModelSelectionUi(parent: View): LinearLayout {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(32), 0, 0)
        }

        // Add title
        val titleText = TextView(context).apply {
            text = getString(R.string.label_gemini_model)
            textSize = 16f
            setPadding(0, 0, 0, dp(16))
        }
        container.addView(titleText)

        // Add current model display
        val currentModelText = TextView(context).apply {
            id = View.generateViewId()
            text = getString(R.string.current_model, viewModel.getGeminiModel())
            setPadding(0, 0, 0, dp(8))
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
            text = getString(R.string.refresh_models)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGeminiModelSelection(container: View) {
        val currentModelText = container.findViewWithTag<TextView>("current_model_text")
        val modelSpinner = container.findViewWithTag<Spinner>("model_spinner")
        val refreshButton = container.findViewWithTag<Button>("refresh_button")

        if (modelSpinner == null || refreshButton == null) return

        wireTooltip(modelSpinner, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_MODEL)
        wireTooltip(refreshButton, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_GEMINI_MODEL)

        // Track real user taps so programmatic selection changes never persist a model.
        var userTouchedSpinner = false

        // Setup spinner
        fun updateModelSpinner(models: List<String>, isLive: Boolean) {
            val adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                models
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modelSpinner.adapter = adapter

            // Migrate off a retired saved model only for a live catalog, never for the fallback.
            val currentModel = viewModel.getGeminiModel()
            val currentIndex = models.indexOf(currentModel)
            when {
                currentIndex >= 0 -> modelSpinner.setSelection(currentIndex)
                isLive && models.isNotEmpty() -> {
                    modelSpinner.setSelection(0)
                    val migrated = models[0]
                    viewModel.saveGeminiModel(migrated)
                    currentModelText?.text = getString(R.string.current_model, migrated)
                }
            }
        }

        // Observe models
        viewModel.geminiModels.observe(viewLifecycleOwner) { options ->
            if (options.models.isNotEmpty()) {
                updateModelSpinner(options.models, options.isLive)
            }
        }

        // Observe loading state
        viewModel.geminiModelsLoading.observe(viewLifecycleOwner) { isLoading ->
            refreshButton.isEnabled = !isLoading
            refreshButton.text = if (isLoading) getString(R.string.loading) else getString(R.string.refresh_models)
        }

        modelSpinner.setOnTouchListener { _, _ ->
            userTouchedSpinner = true
            false
        }

        // Handle model selection
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Ignore programmatic selections; only a real user pick persists the model.
                if (!userTouchedSpinner) return
                val selectedModel = parent?.getItemAtPosition(position) as? String
                if (selectedModel != null && selectedModel != viewModel.getGeminiModel()) {
                    viewModel.saveGeminiModel(selectedModel)
                    currentModelText?.text = getString(R.string.current_model, selectedModel)
                    Toast.makeText(requireContext(), getString(R.string.model_changed, selectedModel), Toast.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Handle refresh button
        refreshButton.setOnClickListener {
            viewModel.fetchGeminiModels()
        }

        // Initial fetch
        if (viewModel.geminiModels.value?.models.isNullOrEmpty()) {
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
