package com.itsaky.androidide.plugins.aiassistant.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.adapters.ChatAdapter
import com.itsaky.androidide.plugins.aiassistant.databinding.FragmentChatBinding
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ChatViewModel
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import java.io.File

/**
 * ChatFragment for Agent chat UI.
 * Provides a full chat interface with LLM integration.
 */
class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ChatViewModel
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var markwon: Markwon
    private val contextFiles = mutableListOf<File>()

    private val tooltipService: IdeTooltipService? by lazy {
        try {
            PluginFragmentHelper.getServiceRegistry(AiAssistantPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            AiAssistantPlugin.getContext()?.logger
                ?.warn("ChatFragment: tooltip service unavailable; long-press help disabled", e)
            null
        }
    }

    /** Shows this plugin's tooltip for [tag] when [view] is long-pressed (Tier 1/2 + guide). */
    private fun wireTooltip(view: View, tag: String) {
        view.setOnLongClickListener { anchor ->
            val service = tooltipService ?: return@setOnLongClickListener false
            service.showTooltip(anchor, AiAssistantPlugin.TOOLTIP_CATEGORY, tag)
            true
        }
    }

    companion object {
        // Test prompt injection (for E2E testing via broadcast receiver)
        @Volatile
        private var pendingTestPrompt: String? = null

        fun injectTestPrompt(prompt: String) {
            pendingTestPrompt = prompt
        }

        fun getPendingTestPrompt(): String? {
            return pendingTestPrompt?.also { pendingTestPrompt = null }
        }
    }


    /**
     * Route inflation through the host so the plugin's views resolve against a Context whose
     * Configuration tracks the IDE's day/night setting — this is what lets values-night/ colors
     * and the DayNight PluginTheme take effect. Replaces the old cloneInContext(pluginContext),
     * which used the plugin's base Context and stayed pinned to light mode.
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
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopProcessing()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMarkwon()
        initializeViewModel()
        if (!viewModel.isStorageInitialized()) {
            viewModel.initializeStorage(requireContext())
        }
        setupToolbar()
        setupRecyclerView()
        setupInputArea()
        setupStatusBar()
        setupBackendIndicator()
        observeViewModel()

        // The settings screen is a DialogFragment, so this fragment's onResume does not fire
        // when it closes. Listen for its dismissal to re-resolve the selected backend (routing +
        // availability) and refresh the indicator label.
        parentFragmentManager.setFragmentResultListener(
            AiSettingsFragment.RESULT_SETTINGS_CLOSED, viewLifecycleOwner
        ) { _, _ ->
            viewModel.checkBackendAvailability()
            viewModel.refreshBackendLabel()
        }

        // Check for test prompt from broadcast receiver (E2E testing)
        injectPendingTestPrompt()
    }

    /**
     * Check for test prompt from broadcast receiver and auto-send if present.
     * Uses SharedPreferences set by TestBroadcastReceiver for reliable communication.
     */
    private fun injectPendingTestPrompt() {
        try {
            // Check SharedPreferences for pending test prompt (set by TestBroadcastReceiver)
            val context = requireContext()
            val prefs = context.getSharedPreferences("test_ai_prefs", android.content.Context.MODE_PRIVATE)
            val pendingPrompt = prefs.getString("pending_prompt", null)
            val shouldAutoSend = prefs.getBoolean("auto_send", false)

            if (!pendingPrompt.isNullOrBlank() && shouldAutoSend) {
                android.util.Log.d("ChatFragment", "📝 Found pending test prompt: '$pendingPrompt'")

                // Inject into input field
                binding.promptInputEdittext.setText(pendingPrompt)
                android.util.Log.d("ChatFragment", "✅ Prompt injected into input field")

                // Auto-send after a short delay to ensure UI is ready
                binding.promptInputEdittext.post {
                    android.util.Log.d("ChatFragment", "🚀 Sending prompt automatically...")
                    binding.sendButton.performClick()

                    // Clear the SharedPreferences after sending
                    prefs.edit().apply {
                        remove("pending_prompt")
                        remove("auto_send")
                        remove("auto_approve")
                        remove("timestamp")
                        apply()
                    }
                    android.util.Log.d("ChatFragment", "🧹 Cleared pending prompt from preferences")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatFragment", "Error checking for pending test prompt: ${e.message}")
        }
    }

    private fun initializeMarkwon() {
        markwon = Markwon.create(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // Check backend availability when fragment becomes visible
        // This ensures we check after all plugins have loaded
        viewModel.checkBackendAvailability()
        // Reflect the currently selected backend (updates after returning from settings).
        viewModel.refreshBackendLabel()
    }

    private fun initializeViewModel() {
        // Pass plugin context getter instead of service directly
        // This allows ViewModel to get service lazily
        viewModel = ViewModelProvider(
            this,
            ChatViewModelFactory { getPluginContext() }
        )[ChatViewModel::class.java]
    }

    private fun getPluginContext(): PluginContext? {
        // Access the plugin context via the companion object
        return com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin.getContext()
    }

    private fun setupRecyclerView() {
        // The adapter inflates item views from parent.context (the RecyclerView's theme-aware
        // Context), so it no longer needs a Context passed in.
        chatAdapter = ChatAdapter(markwon, ::wireTooltip) { action, message ->
            onMessageAction(action, message)
        }
        binding.chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupToolbar() {
        binding.btnOverflowMenu.setOnClickListener { view ->
            // Anchor's Context is the theme-aware plugin Context (inflated via getPluginInflater),
            // so the menu resource resolves and follows the IDE day/night theme.
            val popup = android.widget.PopupMenu(view.context, view)
            popup.menuInflater.inflate(com.itsaky.androidide.plugins.aiassistant.R.menu.chat_overflow_menu, popup.menu)

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    com.itsaky.androidide.plugins.aiassistant.R.id.menu_settings -> {
                        openSettingsFragment()
                        true
                    }
                    com.itsaky.androidide.plugins.aiassistant.R.id.menu_clear_chat -> {
                        viewModel.createNewSession()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
        wireTooltip(binding.btnOverflowMenu, AiAssistantPlugin.TOOLTIP_TAG_CHAT_MENU)
    }

    private fun setupInputArea() {
        binding.promptInputEdittext.doAfterTextChanged { text ->
            binding.sendButton.isEnabled = !text.isNullOrBlank()
        }

        binding.sendButton.setOnClickListener {
            val currentAgentState = viewModel.agentState.value
            when (currentAgentState) {
                is AgentState.Executing, is AgentState.Processing -> {
                    viewModel.stopProcessing()
                }
                else -> {
                    val message = binding.promptInputEdittext.text?.toString() ?: return@setOnClickListener
                    if (message.isNotBlank()) {
                        hideKeyboard()
                        viewModel.sendMessage(message)
                        binding.promptInputEdittext.text?.clear()
                    }
                }
            }
        }

        binding.btnAddContext.setOnClickListener {
            showFilePicker()
        }

        // Anchored on the bar, not promptInputEdittext: long-press there is the paste menu.
        wireTooltip(binding.btnAddContext, AiAssistantPlugin.TOOLTIP_TAG_CONTEXT_FILES)
        wireTooltip(binding.inputBarCard, AiAssistantPlugin.TOOLTIP_TAG_CHAT_INPUT)
        wireTooltip(binding.sendButton, AiAssistantPlugin.TOOLTIP_TAG_CHAT_SEND)
        wireTooltip(binding.backendStatusText, AiAssistantPlugin.TOOLTIP_TAG_SETTINGS_BACKEND)
    }

    private fun setupStatusBar() {
        binding.agentStatusContainer.isVisible = false
    }

    private fun setupBackendIndicator() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeBackendLabel.collect { label ->
                    binding.backendStatusText.text = label
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeMessages() }
                launch { observeAgentState() }
                launch { observePendingApprovalRequest() }
            }
        }
    }

    private suspend fun observeMessages() {
        android.util.Log.d("ChatFragment", "observeMessages: Starting to collect messages")
        viewModel.messages.collect { messages ->
            android.util.Log.d("ChatFragment", "observeMessages: Received ${messages.size} messages")
            messages.forEachIndexed { index, msg ->
                android.util.Log.d("ChatFragment", "  Message $index: sender=${msg.sender}, text=${msg.text.take(50)}")
            }
            binding.emptyChatView.isVisible = messages.isEmpty()
            android.util.Log.d("ChatFragment", "observeMessages: Calling submitList with ${messages.size} messages")
            chatAdapter.submitList(messages) {
                android.util.Log.d("ChatFragment", "observeMessages: submitList callback - scrolling to ${messages.size - 1}")
                if (messages.isNotEmpty()) {
                    binding.chatRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private suspend fun observeAgentState() {
        viewModel.agentState.collect { state ->
            when (state) {
                is AgentState.Idle -> {
                    binding.agentStatusContainer.isVisible = false
                    binding.sendButton.isEnabled = true
                    binding.sendButton.text = "Send"
                }
                is AgentState.Executing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = state.formattedProgress
                    binding.agentStatusTimer.text = state.formattedTiming
                    binding.sendButton.isEnabled = true
                    binding.sendButton.text = "Stop"
                    viewModel.startStateTimer(state)
                }
                is AgentState.Processing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = "Generating response..."
                    binding.agentStatusTimer.text = ""
                    binding.sendButton.isEnabled = true
                    binding.sendButton.text = "Stop"
                }
                is AgentState.Error -> {
                    binding.agentStatusContainer.isVisible = false
                    binding.sendButton.isEnabled = true
                    binding.sendButton.text = "Send"
                    viewModel.stopStateTimer()
                    showErrorSnackbar(state.message)
                }
                else -> {
                    binding.sendButton.isEnabled = false
                    binding.sendButton.text = "Send"
                }
            }
        }
    }

    private suspend fun observePendingApprovalRequest() {
        viewModel.pendingApprovalRequest.collect { request ->
            if (request != null) {
                showApprovalDialog(request)
            }
        }
    }

    private fun showApprovalDialog(request: com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest) {
        val dialog = ApprovalDialogFragment.newInstance(request) { result ->
            viewModel.submitApproval(result)
        }
        dialog.show(parentFragmentManager, "approval_dialog")
    }

    /**
     * Opens the file picker rooted at the open project. The host's [IdeProjectService] is the
     * only source of truth for that root — PathGuard's `System.getProperty` fallback resolves
     * to "/" in the IDE process, which would root the picker at the device filesystem instead
     * of the project. With no project open there is nothing to confine the picker to, so this
     * fails closed and says so rather than opening an unconfined browser.
     */
    private fun showFilePicker() {
        val projectService = getPluginContext()?.services?.get(IdeProjectService::class.java)
        val startPath = projectService?.getCurrentProject()?.rootDir?.absolutePath
        if (startPath.isNullOrBlank()) {
            showInfoSnackbar(getString(R.string.file_picker_error_no_project))
            return
        }

        val dialog = FilePickerDialogFragment.newInstance(startPath) { files ->
            addContextFiles(files)
        }
        dialog.show(parentFragmentManager, "file_picker")
    }

    private fun addContextFiles(files: List<File>) {
        files.forEach { file ->
            if (!contextFiles.contains(file)) {
                contextFiles.add(file)
                addChipForFile(file)
            }
        }
        viewModel.setContextFiles(contextFiles)
    }

    private fun addChipForFile(file: File) {
        val chip = Chip(requireContext()).apply {
            text = file.name
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                contextFiles.remove(file)
                binding.contextChipGroup.removeView(this)
                viewModel.setContextFiles(contextFiles)
            }
        }
        binding.contextChipGroup.addView(chip)
    }

    private fun onMessageAction(action: String, message: com.itsaky.androidide.plugins.aiassistant.models.ChatMessage) {
        when (action) {
            ChatAdapter.ACTION_EDIT -> {
                // Show dialog to edit message
                binding.promptInputEdittext.setText(message.text)
                binding.promptInputEdittext.requestFocus()
            }
            ChatAdapter.ACTION_RETRY -> {
                // Resend the message
                viewModel.sendMessage(message.text)
            }
            ChatAdapter.ACTION_OPEN_SETTINGS -> {
                // Open settings fragment
                openSettingsFragment()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.promptInputEdittext.windowToken, 0)
    }

    private fun openSettingsFragment() {
        val settingsFragment = AiSettingsFragment()
        // Show as dialog fragment to avoid overlapping with chat UI
        settingsFragment.show(parentFragmentManager, "ai_settings")
    }

    /**
     * Surface an [AgentState.Error] as a transient, actionable Snackbar with a shortcut into
     * settings. Snackbar (not Toast) is mandatory here: a Toast built from the plugin's Context
     * crashes the IDE with a SecurityException because the plugin package isn't a real installed
     * UID; Snackbar attaches to the existing host view hierarchy instead.
     */
    private fun showErrorSnackbar(message: String) {
        val binding = _binding ?: return
        Snackbar
            .make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("Settings") { openSettingsFragment() }
            .show()
    }

    private fun showInfoSnackbar(message: String) {
        val binding = _binding ?: return
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

}

/**
 * Factory for creating ChatViewModel with PluginContext dependency.
 */
class ChatViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
