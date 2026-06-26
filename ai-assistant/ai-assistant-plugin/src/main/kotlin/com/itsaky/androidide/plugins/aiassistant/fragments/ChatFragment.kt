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
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.adapters.ChatAdapter
import com.itsaky.androidide.plugins.aiassistant.databinding.FragmentChatBinding
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ChatViewModel
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


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Get plugin context to ensure proper resource inflation
        val pluginContext = getPluginContext()?.androidContext ?: requireContext()

        // Create inflater with plugin context
        val pluginInflater = inflater.cloneInContext(pluginContext)

        _binding = FragmentChatBinding.inflate(pluginInflater, container, false)
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
        setupRecyclerView()
        setupInputArea()
        setupStatusBar()
        setupBackendIndicator()
        observeViewModel()
    }

    private fun initializeMarkwon() {
        markwon = Markwon.create(requireContext())
    }

    override fun onResume() {
        super.onResume()
        // Check backend availability when fragment becomes visible
        // This ensures we check after all plugins have loaded
        viewModel.checkBackendAvailability()
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
        // Use plugin context for adapter to ensure proper resource inflation
        val pluginContext = getPluginContext()?.androidContext ?: requireContext()
        chatAdapter = ChatAdapter(pluginContext, markwon) { action, message ->
            onMessageAction(action, message)
        }
        binding.chatRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
        }

        // DEBUG: Add test message to verify RecyclerView works
        android.util.Log.d("ChatFragment", "RecyclerView setup complete, adding test message")
        val testMessage = com.itsaky.androidide.plugins.aiassistant.models.ChatMessage(
            text = "🔧 DEBUG: If you can see this, the RecyclerView is working!",
            sender = com.itsaky.androidide.plugins.aiassistant.models.Sender.AGENT
        )
        chatAdapter.submitList(listOf(testMessage))
    }

    private fun setupInputArea() {
        binding.promptInputEdittext.doAfterTextChanged { text ->
            binding.sendButton.isEnabled = !text.isNullOrBlank()
        }

        binding.sendButton.setOnClickListener {
            val message = binding.promptInputEdittext.text?.toString() ?: return@setOnClickListener
            if (message.isNotBlank()) {
                viewModel.sendMessage(message)
                binding.promptInputEdittext.text?.clear()
            }
        }

        binding.btnAddContext.setOnClickListener {
            showFilePicker()
        }
    }

    private fun setupStatusBar() {
        binding.agentStatusContainer.isVisible = false
    }

    private fun setupBackendIndicator() {
        binding.backendStatusText.text = "Gemini"
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // TEMP: Commented out to test if RecyclerView can display messages
                // launch { observeMessages() }
                launch { observeAgentState() }
                launch { observePendingApprovalRequest() }
            }
        }
    }

    private suspend fun observeMessages() {
        viewModel.messages.collect { messages ->
            binding.emptyChatView.isVisible = messages.isEmpty()
            chatAdapter.submitList(messages) {
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
                }
                is AgentState.Executing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = state.formattedProgress
                    binding.agentStatusTimer.text = state.formattedTiming
                    binding.sendButton.isEnabled = false
                    viewModel.startStateTimer(state)
                }
                is AgentState.Processing -> {
                    binding.agentStatusContainer.isVisible = true
                    binding.agentStatusMessage.text = "Generating response..."
                    binding.agentStatusTimer.text = ""
                    binding.sendButton.isEnabled = false
                }
                is AgentState.Error -> {
                    binding.agentStatusContainer.isVisible = false
                    binding.sendButton.isEnabled = true
                    viewModel.stopStateTimer()
                }
                else -> {
                    binding.sendButton.isEnabled = false
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

    private fun showFilePicker() {
        // Start from AndroidIDE projects directory by default
        val startPath = "/storage/emulated/0/AndroidIDEProjects"

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

    private fun openSettingsFragment() {
        val settingsFragment = AiSettingsFragment()
        // Show as dialog fragment to avoid overlapping with chat UI
        settingsFragment.show(parentFragmentManager, "ai_settings")
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
