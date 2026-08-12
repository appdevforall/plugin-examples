package com.itsaky.androidide.plugins.aiagentopenai.settings

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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentopenai.R
import com.itsaky.androidide.plugins.aiagentopenai.plugin.OpenAiPlugin
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * This backend's settings pane, mounted by whichever screen offers a backend selector.
 *
 * Named to the host through `OpenAiBackend.getSettingsFragmentClassName()`, loaded with this
 * plugin's own classloader and inflated against this plugin's own resources — so the consumer needs
 * to know nothing about API keys, server URLs or model catalogs.
 */
class OpenAiSettingsFragment : Fragment() {

    private lateinit var viewModel: OpenAiSettingsViewModel
    private var tooltipService: IdeTooltipService? = null

    /**
     * Re-reads the server URL and re-dresses the key section for it. Set once the key section is
     * built, so the server section can call it whenever the URL changes.
     */
    private var onServerChanged: ((String) -> Unit)? = null

    /**
     * Set while this pane is on screen, so [onResume] can nudge the user towards **Save** after
     * they come back from the key page. Cleared when the view is destroyed — it captures views, so
     * holding it any longer would leak them.
     */
    private var onPaneResume: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            tooltipService = PluginFragmentHelper.getServiceRegistry(OpenAiPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            // Tooltip help is optional; long-press simply shows nothing when it's unavailable.
            OpenAiPlugin.getContext()?.logger
                ?.warn("OpenAiSettingsFragment: tooltip service unavailable", e)
        }
    }

    /**
     * Route inflation through the host so this pane resolves against *this* plugin's resources and
     * a Context whose Configuration tracks the IDE's day/night setting. The inflater inherited from
     * the hosting screen belongs to that plugin and cannot see this one's layouts.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(OpenAiPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_openai_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(
            this,
            OpenAiSettingsViewModelFactory { OpenAiPlugin.getContext() }
        )[OpenAiSettingsViewModel::class.java]

        // The key section publishes onServerChanged, so it is built before the server section that
        // fires it, and before the first call below that dresses the pane for the saved server.
        setupApiKeyUi(view)
        setupServerUi(view)
        setupModelUi(view)
        setupConnectionTest(view)
        onServerChanged?.invoke(viewModel.getBaseUrl())
    }

    override fun onResume() {
        super.onResume()
        onPaneResume?.invoke()
    }

    override fun onDestroyView() {
        // Drops the captured pane views along with the callbacks.
        onPaneResume = null
        onServerChanged = null
        setSecureWindow(false)
        super.onDestroyView()
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide button). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, OpenAiPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    /**
     * Show [message] on [target] with an optional leading status icon.
     *
     * @param icon leading status drawable, or 0 for the states that don't warrant one
     */
    private fun showStatus(target: TextView, message: String, @DrawableRes icon: Int = 0) {
        target.text = message
        // Relative (not left/right) so the icon follows the layout direction in RTL locales.
        target.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        target.visibility = View.VISIBLE
    }

    /** Drop a status line that no longer describes what is on screen. */
    private fun hideStatus(target: TextView) {
        target.visibility = View.GONE
        target.text = ""
        target.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
    }

    // --- Server -------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupServerUi(view: View) {
        val presetSpinner = view.findViewById<Spinner>(R.id.openai_preset_spinner)
        val urlInput = view.findViewById<EditText>(R.id.openai_base_url_input)
        val saveButton = view.findViewById<Button>(R.id.btn_save_server)
        val statusText = view.findViewById<TextView>(R.id.openai_server_status_text)
        val serverLabel = view.findViewById<TextView>(R.id.openai_server_label)

        listOf<View>(urlInput, saveButton, serverLabel)
            .forEach { wireTooltip(it, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_SERVER) }
        wireTooltip(presetSpinner, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_PRESET)

        urlInput.setText(viewModel.getBaseUrl())

        val presetLabels = ServerPresets.ALL.map { getString(it.labelRes) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter
        presetSpinner.setSelection(ServerPresets.indexOf(viewModel.getBaseUrl()), false)

        // Track real taps so restoring the spinner's position never overwrites the URL field.
        var userTouchedPresets = false
        presetSpinner.setOnTouchListener { _, _ ->
            userTouchedPresets = true
            false
        }
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!userTouchedPresets) return
                // A preset only fills the field; the user still has to save it.
                ServerPresets.ALL.getOrNull(position)?.url?.let { urlInput.setText(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Re-dresses the key section as the URL is typed or a preset fills it, so picking Ollama
        // stops asking for a key immediately rather than after a save.
        urlInput.doAfterTextChanged { text ->
            onServerChanged?.invoke(text?.toString().orEmpty())
        }

        saveButton.setOnClickListener {
            when (val result = viewModel.saveBaseUrl(urlInput.text.toString())) {
                is BaseUrlResult.Accepted -> {
                    urlInput.setText(result.url)
                    presetSpinner.setSelection(ServerPresets.indexOf(result.url), false)
                    showStatus(
                        statusText,
                        getString(R.string.msg_server_saved, result.url),
                        R.drawable.ic_key_verified
                    )
                    if (result.cleartext && !result.loopback && !viewModel.isCleartextAcknowledged()) {
                        warnAboutCleartext()
                    }
                }

                is BaseUrlResult.Rejected -> showStatus(
                    statusText,
                    getString(rejectionMessage(result.reason)),
                    R.drawable.ic_key_rejected
                )
            }
        }
    }

    /** The string explaining why a URL was refused. */
    private fun rejectionMessage(reason: BaseUrlResult.Reason): Int = when (reason) {
        BaseUrlResult.Reason.BLANK -> R.string.msg_server_blank
        BaseUrlResult.Reason.MALFORMED -> R.string.msg_server_malformed
        BaseUrlResult.Reason.NO_HOST -> R.string.msg_server_no_host
        BaseUrlResult.Reason.CLEARTEXT_PUBLIC -> R.string.msg_server_cleartext_public
    }

    /**
     * Warn once that traffic to a LAN address is unencrypted.
     *
     * Allowed rather than blocked: reaching Ollama on the user's own PC is the point of the URL
     * field, and that traffic never leaves the local network.
     */
    private fun warnAboutCleartext() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_cleartext_server)
            .setMessage(R.string.msg_cleartext_server)
            .setPositiveButton(R.string.action_understood) { _, _ ->
                viewModel.markCleartextAcknowledged()
            }
            .show()
    }

    // --- API key ------------------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun setupApiKeyUi(view: View) {
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.openai_api_key_layout)
        val apiKeyInput = view.findViewById<EditText>(R.id.openai_api_key_input)
        val toggleVisibilityButton = view.findViewById<ImageButton>(R.id.btn_toggle_api_key_visibility)
        val saveButton = view.findViewById<Button>(R.id.btn_save_api_key)
        val editButton = view.findViewById<Button>(R.id.btn_edit_api_key)
        val clearButton = view.findViewById<Button>(R.id.btn_clear_api_key)
        val statusTextView = view.findViewById<TextView>(R.id.openai_api_key_status_text)
        val getKeyButton = view.findViewById<Button>(R.id.btn_get_key)
        val verificationText = view.findViewById<TextView>(R.id.openai_key_verification_text)
        val keyLabel = view.findViewById<TextView>(R.id.openai_api_key_label)
        val keySection = view.findViewById<LinearLayout>(R.id.openai_key_section)
        val keyNotNeededText = view.findViewById<TextView>(R.id.openai_key_not_needed_text)

        // Not on apiKeyInput: long-press there is the paste menu, and a key is pasted.
        listOf<View>(
            toggleVisibilityButton, saveButton, editButton, clearButton, statusTextView,
            verificationText, keyLabel
        ).forEach { wireTooltip(it, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_KEY) }
        wireTooltip(getKeyButton, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_GET_KEY)

        // Whether the *currently typed* server needs a key, so Save can judge a blank field
        // against the server the user is configuring rather than the one last saved.
        var keyRequirement = BaseUrlPolicy.keyRequirement(viewModel.getBaseUrl())

        /**
         * Dress the key section for [serverUrl].
         *
         * A local server collapses the whole block to one muted line: an empty, mandatory-looking
         * key field is the single most confusing thing this pane can show, because a local Ollama
         * needs no credential at all.
         */
        onServerChanged = { serverUrl ->
            keyRequirement = BaseUrlPolicy.keyRequirement(serverUrl)
            val needsKey = keyRequirement != KeyRequirement.NOT_NEEDED
            // A key already stored still has to be reachable, or it could never be cleared.
            val show = needsKey || viewModel.hasStoredApiKey()
            keySection.visibility = if (show) View.VISIBLE else View.GONE
            keyNotNeededText.visibility = if (show) View.GONE else View.VISIBLE
            keyLabel.setText(
                if (keyRequirement == KeyRequirement.REQUIRED) {
                    R.string.label_openai_api_key_required
                } else {
                    R.string.label_openai_api_key_optional
                }
            )
            // Only OpenAI's own page is linked, so the button is meaningless elsewhere.
            getKeyButton.visibility =
                if (keyRequirement == KeyRequirement.REQUIRED) View.VISIBLE else View.GONE
        }

        // "Get API key" visibility is owned by onServerChanged, not by the edit state.
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
            val savedApiKey = viewModel.getApiKey()
            val hasKey = !savedApiKey.isNullOrBlank()
            updateUiState(isEditing = !hasKey)
            if (hasKey) {
                statusTextView.text = savedApiKeyStatusText()
            } else {
                apiKeyInput.setText("")
                // A stored-but-undecryptable key also reads as null; warn as the Edit path does.
                if (viewModel.hasStoredApiKey()) {
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

        getKeyButton.setOnClickListener { openKeyPage() }

        // Coming back from the browser, point at the next step; the clipboard is never read.
        onPaneResume = {
            // Kept on the ViewModel so a rotation while the browser is in front doesn't lose it.
            if (viewModel.sentUserToKeyPage) {
                viewModel.sentUserToKeyPage = false
                // With a key already stored the field is hidden, so the next tap is Edit.
                showStatus(
                    verificationText,
                    if (apiKeyLayout.visibility == View.VISIBLE) {
                        getString(R.string.msg_key_hint_paste_into_field)
                    } else {
                        getString(R.string.msg_key_hint_edit_first)
                    }
                )
            }
        }

        /** Enable or disable everything that would race the in-flight check. */
        fun setKeyEntryEnabled(enabled: Boolean) {
            saveButton.isEnabled = enabled
            getKeyButton.isEnabled = enabled
            apiKeyInput.isEnabled = enabled
        }

        /**
         * Encrypt and store [apiKey], then reflect the outcome. Only ever reached for a key the
         * server confirmed, or one the user chose to keep after an inconclusive check.
         */
        suspend fun persistKey(
            apiKey: String,
            verified: Boolean,
            resultText: String,
            @DrawableRes resultIcon: Int
        ) {
            if (!viewModel.saveApiKey(apiKey, verified)) {
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
            showStatus(verificationText, resultText, resultIcon)
            // A different key can reach a different set of models, so the picker is re-fetched.
            viewModel.fetchModels()
        }

        /**
         * Offer to keep a key that could not be checked. Distinct from a rejection: refusing a good
         * key because the server is offline would leave the plugin unconfigurable, so this gets the
         * muted "unchecked" icon and a key the server actually refused never reaches here.
         */
        fun confirmSaveUnverified(apiKey: String, reason: String) {
            showStatus(verificationText, reason, R.drawable.ic_key_unchecked)
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
            if (apiKey.isBlank()) {
                // Blank is a legitimate configuration for a server that needs no credential.
                if (keyRequirement == KeyRequirement.REQUIRED) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.msg_api_key_required_for_openai),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    viewModel.clearApiKey()
                    showStatus(verificationText, getString(R.string.msg_key_not_needed))
                }
                return@setOnClickListener
            }
            setKeyEntryEnabled(false)
            showStatus(verificationText, getString(R.string.msg_verifying_key))
            viewLifecycleOwner.lifecycleScope.launch {
                val verdict = try {
                    viewModel.verifyConnection(apiKey)
                } finally {
                    setKeyEntryEnabled(true)
                }
                when (verdict) {
                    // Model count omitted: the user saved a key, not asked for a catalog.
                    is ConnectionVerification.Verified -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // A rate-limited key is a working key, so it gets the same icon as a clean pass.
                    ConnectionVerification.RateLimited -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_key_verified_rate_limited),
                        resultIcon = R.drawable.ic_key_verified
                    )

                    // Nothing is written: a definitive refusal would only resurface mid-chat.
                    ConnectionVerification.Rejected -> {
                        showStatus(
                            verificationText,
                            getString(R.string.msg_key_rejected),
                            R.drawable.ic_key_rejected
                        )
                        apiKeyInput.requestFocus()
                    }

                    // The server answered, so the key travelled fine; it just has no catalog.
                    ConnectionVerification.NoModels -> persistKey(
                        apiKey,
                        verified = true,
                        resultText = getString(R.string.msg_server_no_models),
                        resultIcon = R.drawable.ic_key_unchecked
                    )

                    ConnectionVerification.EndpointNotFound ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_server_endpoint_404))

                    ConnectionVerification.Unreachable ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_server_unreachable))

                    ConnectionVerification.Unknown ->
                        confirmSaveUnverified(apiKey, getString(R.string.msg_key_uncheckable))
                }
            }
        }

        // Reveal the (already-fetched) key in an editable, focused field.
        fun revealEditMode(apiKey: String) {
            apiKeyInput.setText(apiKey)
            apiKeyInput.setSelection(apiKey.length)
            // The old verdict described the stored key, which is about to change.
            hideStatus(verificationText)
            updateUiState(isEditing = true)
            isKeyVisible = false
            applyKeyVisibility()
            apiKeyInput.requestFocus()
        }

        editButton.setOnClickListener {
            editButton.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                val apiKey = try {
                    viewModel.getApiKey()
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
            viewModel.clearApiKey()
            Toast.makeText(
                requireContext(),
                getString(R.string.msg_api_key_cleared),
                Toast.LENGTH_SHORT
            ).show()
            hideStatus(verificationText)
            updateUiState(isEditing = true)
            apiKeyInput.setText("")
        }
    }

    /**
     * Status line for a stored key: dated when the save time is known, generic otherwise, and
     * saying "verified" only for a key the server actually confirmed — a key kept through the
     * save-anyway path was never checked and must not claim otherwise.
     */
    private fun savedApiKeyStatusText(): String {
        val timestamp = viewModel.getApiKeySaveTimestamp()
        val verified = viewModel.isKeyVerified()
        if (timestamp <= 0) return getString(R.string.msg_api_key_is_saved)
        val savedDate = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        return if (verified) {
            getString(R.string.msg_api_key_verified_on, savedDate)
        } else {
            getString(R.string.msg_api_key_saved_on, savedDate)
        }
    }

    // --- Model --------------------------------------------------------------------------------

    /**
     * One editable dropdown, not a field beside a spinner.
     *
     * Free text has to work — a local server's model is whatever the user pulled, and plenty of
     * compatible servers do not implement `/v1/models` at all — but the discovered list belongs in
     * the same control rather than a second one. The value is saved on pick, on IME Done and on
     * focus loss, so there is no Save button either.
     */
    private fun setupModelUi(view: View) {
        val modelInput = view.findViewById<AutoCompleteTextView>(R.id.openai_model_input)
        val modelLabel = view.findViewById<TextView>(R.id.openai_model_label)
        val modelHint = view.findViewById<TextView>(R.id.openai_model_hint_text)

        listOf<View>(modelLabel, modelInput, modelHint)
            .forEach { wireTooltip(it, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_MODEL) }

        modelInput.setText(viewModel.getModel())

        /** Persist what is typed, ignoring a blank field rather than storing an unusable model. */
        fun commitTypedModel() {
            val typed = modelInput.text.toString().trim()
            if (typed.isEmpty() || typed == viewModel.getModel()) return
            viewModel.saveModel(typed)
        }

        modelInput.setOnEditorActionListener { _, _, _ ->
            commitTypedModel()
            false
        }
        modelInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitTypedModel()
        }
        // Tapping the field opens the list; completionThreshold=0 alone waits for a keystroke.
        modelInput.setOnClickListener { modelInput.showDropDown() }
        modelInput.setOnItemClickListener { _, _, _, _ -> commitTypedModel() }

        // A server that does not offer the saved model retires it; the field must show what will
        // actually be requested, and silently keeping the old id is what 404s on the first message.
        viewModel.selectedModel.observe(viewLifecycleOwner) { model ->
            val shown = modelInput.text.toString()
            if (shown == model || modelInput.hasFocus()) return@observe
            modelInput.setText(model)
            if (shown.isNotBlank()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.msg_model_switched, model),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        viewModel.models.observe(viewLifecycleOwner) { options ->
            // Cleared, not left stale: an empty list means the server changed and the old catalog
            // no longer describes it, so offering it would suggest models that will 404.
            modelInput.setAdapter(
                if (options.models.isEmpty()) {
                    null
                } else {
                    ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, options.models)
                }
            )
            // Keyed on whether there is a list, not on whether it is live: a remembered list is
            // still a list to tap, and telling the user to test the connection would be wrong.
            modelHint.text = getString(
                if (options.models.isEmpty()) {
                    R.string.hint_openai_model_help
                } else {
                    R.string.hint_openai_model_live
                }
            )
        }
    }

    // --- Connection test ----------------------------------------------------------------------

    /**
     * The one server round trip this pane makes.
     *
     * Testing the connection and listing the models were two buttons issuing the identical
     * `GET {baseUrl}/models`, so they are one: the verdict is reported and, when the server
     * answered with a catalog, it fills the model dropdown.
     */
    private fun setupConnectionTest(view: View) {
        val testButton = view.findViewById<Button>(R.id.btn_test_connection)
        val statusText = view.findViewById<TextView>(R.id.openai_connection_status_text)
        val urlInput = view.findViewById<EditText>(R.id.openai_base_url_input)
        val apiKeyInput = view.findViewById<EditText>(R.id.openai_api_key_input)
        val apiKeyLayout = view.findViewById<LinearLayout>(R.id.openai_api_key_layout)

        wireTooltip(testButton, OpenAiPlugin.TOOLTIP_TAG_SETTINGS_TEST)

        viewModel.modelsLoading.observe(viewLifecycleOwner) { isLoading ->
            testButton.isEnabled = !isLoading
            testButton.text =
                if (isLoading) getString(R.string.loading) else getString(R.string.btn_test_connection)
        }

        testButton.setOnClickListener {
            testButton.isEnabled = false
            showStatus(statusText, getString(R.string.msg_testing_connection))
            viewLifecycleOwner.lifecycleScope.launch {
                // Tests what is on screen: a typo is worth catching before it is saved.
                val url = urlInput.text.toString().trim().ifEmpty { viewModel.getBaseUrl() }
                val typedKey = apiKeyInput.text.toString().trim()
                val key = if (apiKeyLayout.visibility == View.VISIBLE && typedKey.isNotEmpty()) {
                    typedKey
                } else {
                    viewModel.getApiKey().orEmpty()
                }
                val verdict = try {
                    viewModel.verifyConnection(key, url)
                } finally {
                    testButton.isEnabled = true
                }
                val (message, icon) = describe(verdict)
                showStatus(statusText, message, icon)
                // Same request either way, so a successful test has already earned the catalog.
                if (verdict is ConnectionVerification.Verified) viewModel.fetchModels()
            }
        }
    }

    /** One line and one icon for a connection verdict. */
    private fun describe(verdict: ConnectionVerification): Pair<String, Int> = when (verdict) {
        is ConnectionVerification.Verified -> getString(
            R.string.msg_connection_ok,
            verdict.modelCount
        ) to R.drawable.ic_key_verified

        ConnectionVerification.RateLimited ->
            getString(R.string.msg_key_verified_rate_limited) to R.drawable.ic_key_verified

        ConnectionVerification.NoModels ->
            getString(R.string.msg_server_no_models) to R.drawable.ic_key_unchecked

        ConnectionVerification.Rejected ->
            getString(R.string.msg_key_rejected) to R.drawable.ic_key_rejected

        ConnectionVerification.EndpointNotFound ->
            getString(R.string.msg_server_endpoint_404) to R.drawable.ic_key_rejected

        ConnectionVerification.Unreachable ->
            getString(R.string.msg_server_unreachable) to R.drawable.ic_key_rejected

        ConnectionVerification.Unknown ->
            getString(R.string.msg_key_uncheckable) to R.drawable.ic_key_unchecked
    }

    // --- Window and browser -------------------------------------------------------------------

    /**
     * Add or clear [WindowManager.LayoutParams.FLAG_SECURE] on the host activity's window.
     *
     * Set while the key is in clear text, or screenshots and the recents thumbnail would capture
     * it. Cleared in [onDestroyView], since the window outlives this fragment's view.
     *
     * @param secure true to block capture, false to allow it again
     */
    private fun setSecureWindow(secure: Boolean) {
        val window = activity?.window ?: return
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
     * Open OpenAI's API keys page in the *system* browser.
     *
     * A real browser, not a WebView: sign-in is blocked in embedded WebViews, and the user should
     * see OpenAI's own URL bar. With no browser at all, the URL is copied instead.
     */
    private fun openKeyPage() {
        val url = OpenAiKeyOnboarding.API_KEYS_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onSuccess { viewModel.sentUserToKeyPage = true }
            .onFailure { error ->
                OpenAiPlugin.getContext()?.logger
                    ?.warn("OpenAiSettingsFragment: no browser could open the key page", error)
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
     * Only ever used for the public key-page URL — never for a key, which would put the secret
     * somewhere every app on the device can read it.
     *
     * @return true when the clipboard accepted the value
     */
    private fun copyToClipboard(text: String): Boolean {
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.openai_clip_label), text))
        }.isSuccess
    }
}

/**
 * Factory for creating [OpenAiSettingsViewModel] with its PluginContext dependency.
 */
class OpenAiSettingsViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OpenAiSettingsViewModel::class.java)) {
            return OpenAiSettingsViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
