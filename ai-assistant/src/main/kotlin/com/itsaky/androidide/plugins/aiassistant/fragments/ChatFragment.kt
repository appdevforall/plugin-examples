package com.itsaky.androidide.plugins.aiassistant.fragments

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin
import com.itsaky.androidide.plugins.aiassistant.BuildConfig
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.adapters.ChatAdapter
import com.itsaky.androidide.plugins.aiassistant.databinding.FragmentChatBinding
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.models.isRunning
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ChatViewModel
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import java.io.File

/**
 * ChatFragment for Agent chat UI.
 * Provides a full chat interface with LLM integration.
 */
class ChatFragment : Fragment(), ApprovalDialogFragment.Host {

    private companion object {
        /** Tag the approval dialog is shown under, so it can be found again after recreation. */
        const val APPROVAL_DIALOG_TAG = "approval_dialog"
    }

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ChatViewModel
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var markwon: Markwon
    private val contextFiles = mutableListOf<File>()

    private var composer: ComposerAutoHideController? = null

    /** The message list's layout-declared padding, before any cutout inset is added. */
    private val basePadding = Rect()

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

    /**
     * Routes inflation through the host so views resolve against a Context whose Configuration
     * tracks the IDE's day/night setting, which is what lets values-night/ colors and the DayNight
     * PluginTheme take effect. The old cloneInContext() stayed pinned to light mode.
     * @param savedInstanceState forwarded to the superclass inflater.
     * @return the theme-aware inflater the plugin's layouts must be inflated with.
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
        if (::chatAdapter.isInitialized) {
            chatAdapter.stopAllAnimations()
        }
        super.onDestroyView()
        viewModel.stopProcessing()
        composer?.detach()
        composer = null
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The process can be killed while backgrounded even though rotation never recreates us.
        composer?.saveState(outState)
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
        setupCutoutPadding()
        setupComposer(savedInstanceState)
        setupStatusBar()
        setupBackendIndicator()
        observeViewModel()

        // Check for test prompt from broadcast receiver (E2E testing)
        injectPendingTestPrompt()
    }

    /**
     * Auto-sends a test prompt left in SharedPreferences by TestBroadcastReceiver. Debug builds
     * only: this drives the agent, and its file-mutating tools, with no user gesture, so it must
     * not exist in a released plugin.
     */
    private fun injectPendingTestPrompt() {
        if (!BuildConfig.DEBUG) return
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
        // On becoming visible, so the check runs after every plugin has loaded.
        viewModel.checkBackendAvailability()
        // Re-resolve the selected backend here: the settings screen is a separate activity that
        // fully covers chat, so returning from it always delivers onResume.
        viewModel.refreshBackendLabel()
    }

    private fun initializeViewModel() {
        // A context getter, not the service, so the ViewModel resolves it lazily.
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
        // Item views inflate from parent.context, so no Context needs passing in.
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
            // The anchor's Context is theme-aware, so the menu follows the IDE day/night theme.
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
        binding.sendButton.setOnClickListener {
            if (viewModel.agentState.value.isRunning) {
                viewModel.stopProcessing()
            } else {
                val message = binding.promptInputEdittext.text?.toString() ?: return@setOnClickListener
                if (message.isNotBlank()) {
                    composer?.hideKeyboard()
                    viewModel.sendMessage(message)
                    binding.promptInputEdittext.text?.clear()
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

    /**
     * Records the message list's own padding so the cutout inset is added to it rather than
     * replacing it, and stays correct however many inset passes the window makes.
     */
    private fun setupCutoutPadding() {
        val list = binding.chatRecyclerView
        basePadding.set(list.paddingLeft, list.paddingTop, list.paddingRight, list.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            applyCutoutPadding(insets)
            insets
        }
        // No dispatched insets to read on the first attach, so take them from the window.
        binding.root.doOnAttach { view ->
            ViewCompat.getRootWindowInsets(view)?.let(::applyCutoutPadding)
        }
    }

    /**
     * Holds the message list clear of the camera cutout. Portrait puts it in the status bar and
     * these insets come back zero; landscape moves it onto a side edge, right where message text
     * would otherwise start. The toolbar and composer keep their own edge-to-edge alignment.
     */
    private fun applyCutoutPadding(insets: WindowInsetsCompat) {
        val binding = _binding ?: return
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        binding.chatRecyclerView.setPadding(
            basePadding.left + cutout.left,
            basePadding.top,
            basePadding.right + cutout.right,
            basePadding.bottom,
        )
    }

    /**
     * On a short screen the composer folds away so the history gets its height, and a floating
     * chevron brings it back. Everywhere else the composer stays pinned and the chevron is absent.
     */
    private fun setupComposer(savedInstanceState: Bundle?) {
        val controller = ComposerAutoHideController(
            binding,
            viewLifecycleOwner.lifecycleScope,
            ::wireTooltip,
        )
        // The fragment's own Resources, which is the host activity's and tracks rotation.
        controller.attach(savedInstanceState, resources.configuration)
        composer = controller
    }

    /**
     * EditorActivity handles orientation itself, so rotating never recreates this fragment and an
     * alternative-resource bucket would stay frozen at the orientation it was inflated in. The one
     * thing that must track rotation is re-applied here instead.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val binding = _binding ?: return
        composer?.onConfigurationChanged(newConfig)
        // Posted so the window has published the rotated cutout before it is read back.
        binding.root.post {
            val root = _binding?.root ?: return@post
            ViewCompat.getRootWindowInsets(root)?.let(::applyCutoutPadding)
        }
    }

    private fun setupStatusBar() {
        binding.agentStatusContainer.isVisible = false
    }

    private fun setupBackendIndicator() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeBackendLabel.collect { label ->
                    _binding?.backendStatusText?.text = label
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
            val binding = _binding ?: return@collect
            android.util.Log.d("ChatFragment", "observeMessages: Received ${messages.size} messages")
            messages.forEachIndexed { index, msg ->
                android.util.Log.d("ChatFragment", "  Message $index: sender=${msg.sender}, text=${msg.text.take(50)}")
            }
            binding.emptyChatView.isVisible = messages.isEmpty()
            android.util.Log.d("ChatFragment", "observeMessages: Calling submitList with ${messages.size} messages")
            // Sampled before the list changes: streaming re-emits on every token, so scrolling
            // unconditionally would drag the user back down whenever they scrolled up to read.
            val stickToBottom = binding.chatRecyclerView.isAtBottom()
            chatAdapter.submitList(messages) {
                android.util.Log.d("ChatFragment", "observeMessages: submitList callback - scrolling to ${messages.size - 1}")
                if (stickToBottom && messages.isNotEmpty()) {
                    // Null after onDestroyView: submitList posts this callback.
                    _binding?.chatRecyclerView?.scrollToPosition(messages.lastIndex)
                }
            }
        }
    }

    /** True while the newest message is fully visible, i.e. the user is not reading back. */
    private fun RecyclerView.isAtBottom(): Boolean = !canScrollVertically(1)

    private suspend fun observeAgentState() {
        viewModel.agentState.collect { state ->
            val binding = _binding ?: return@collect
            when (state) {
                is AgentState.Idle -> binding.agentStatusContainer.isVisible = false
                is AgentState.Executing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = state.formattedProgress
                    binding.agentStatusTimer.text = state.formattedTiming
                    viewModel.startStateTimer(state)
                }
                is AgentState.Processing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = getString(R.string.generating_response)
                    binding.agentStatusTimer.text = ""
                }
                is AgentState.Error -> {
                    binding.agentStatusContainer.isVisible = false
                    viewModel.stopStateTimer()
                    showErrorSnackbar(state.message)
                }
                else -> Unit
            }
            // The composer owns the send/stop button, since Stop is what pins it open.
            composer?.onAgentRunningChanged(state.isRunning)
        }
    }

    /**
     * The approval dialog currently on screen, looked up by tag rather than held in a field:
     * after a configuration change this fragment is a new instance, and a field would be null
     * while the framework-recreated dialog is still up — leaving it un-dismissable.
     */
    private fun currentApprovalDialog(): ApprovalDialogFragment? =
        childFragmentManager.findFragmentByTag(APPROVAL_DIALOG_TAG) as? ApprovalDialogFragment

    private suspend fun observePendingApprovalRequest() {
        viewModel.pendingApprovalRequest.collect { request ->
            if (request != null) {
                showApprovalDialog(request)
            } else {
                // Withdrawn by Stop or Clear Chat: nothing awaits it, so don't strand the dialog.
                currentApprovalDialog()?.dismissAllowingStateLoss()
            }
        }
    }

    private fun showApprovalDialog(request: com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest) {
        // childFragmentManager makes this fragment the parent, which is how Host is resolved.
        if (currentApprovalDialog() != null) return
        ApprovalDialogFragment.newInstance(request).show(childFragmentManager, APPROVAL_DIALOG_TAG)
    }

    override fun onApprovalDecision(
        result: com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult,
        correction: String?,
    ) {
        viewModel.submitApproval(result, correction)
    }

    /**
     * Opens the file picker rooted at the open project. [IdeProjectService] is the only source of
     * truth for that root; PathGuard's property fallback resolves to "/" here, which would root the
     * picker at the whole device. With no project open this fails closed and says so.
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
        composer?.pauseUntilClosed(dialog.lifecycle)
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
                updateContextChipVisibility()
            }
        }
        binding.contextChipGroup.addView(chip)
        updateContextChipVisibility()
    }

    /**
     * Keeps the chip row out of the layout while empty; in a short bottom sheet the row it would
     * otherwise occupy comes straight out of the chat history's height.
     */
    private fun updateContextChipVisibility() {
        binding.contextChipScroll.isVisible = binding.contextChipGroup.childCount > 0
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

    /**
     * Open the Agent settings screen — the same one Preferences → Configuration → Agent opens, so
     * there is one implementation of it. The host mounts it full-screen in PluginScreenActivity;
     * returning from it gives this fragment a real onResume.
     */
    private fun openSettingsFragment() {
        val opened = PluginFragmentHelper.getServiceRegistry(AiAssistantPlugin.PLUGIN_ID)
            ?.get(IdeUIService::class.java)
            ?.openPluginScreen(
                AiAssistantPlugin.PLUGIN_ID,
                AiSettingsFragment::class.java.name,
                getString(R.string.pref_agent_title)
            ) ?: false
        if (!opened) {
            AiAssistantPlugin.getContext()?.logger
                ?.warn("ChatFragment: could not open the Agent settings screen")
            showInfoSnackbar(getString(R.string.msg_settings_unavailable))
        }
    }

    /**
     * Surfaces an [AgentState.Error] as a Snackbar with a shortcut into settings. Snackbar, never
     * Toast: a Toast built from the plugin's Context crashes the IDE with a SecurityException,
     * since the plugin package is not a real installed UID.
     * @param message the error text to show.
     */
    private fun showErrorSnackbar(message: String) {
        val binding = _binding ?: return
        Snackbar
            .make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.menu_settings)) { openSettingsFragment() }
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
