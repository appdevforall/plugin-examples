package com.itsaky.androidide.plugins.aiassistant.fragments

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.view.ViewGroup.MarginLayoutParams
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.io.File
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.itsaky.androidide.plugins.aiassistant.adapters.ChatAdapter
import io.noties.markwon.Markwon
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.viewmodel.ChatViewModel
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult
import kotlinx.coroutines.launch

/**
 * ChatFragment for Agent chat UI.
 * Provides a full chat interface with LLM integration.
 */
class ChatFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var markwon: Markwon
    private lateinit var chipGroup: ChipGroup
    private lateinit var addContextButton: ImageButton
    private val contextFiles = mutableListOf<File>()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FrameLayout(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeMarkwon()
        initializeViewModel()
        buildUI()
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

    private fun buildUI() {
        val rootView = view as? ViewGroup ?: return

        val mainContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // Toolbar with settings button
        val toolbar = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        val toolbarTitle = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = "AI Chat"
            textSize = 18f
            setPadding(8, 8, 8, 8)
        }
        toolbar.addView(toolbarTitle)

        settingsButton = ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Use Android's built-in more_vert icon (3 dots)
            setImageResource(android.R.drawable.ic_menu_more)
            setOnClickListener { showSettingsMenu(it) }
            background = null // Remove default button background
            setPadding(16, 16, 16, 16)
        }
        toolbar.addView(settingsButton)

        mainContainer.addView(toolbar)

        // Status bar (only for errors and backend availability)
        statusTextView = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 14f
            setPadding(8, 8, 8, 8)
            visibility = View.GONE
        }
        mainContainer.addView(statusTextView)

        // Context chips container
        val contextContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 0, 8, 0)
            visibility = View.GONE
        }

        chipGroup = ChipGroup(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        contextContainer.addView(chipGroup)

        addContextButton = ImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setImageResource(android.R.drawable.ic_input_add)
            background = null
            setPadding(16, 16, 16, 16)
            setOnClickListener { showFilePicker() }
        }
        contextContainer.addView(addContextButton)

        mainContainer.addView(contextContainer)

        // Messages RecyclerView
        // Use plugin context for adapter to ensure proper resource inflation
        val pluginContext = getPluginContext()?.androidContext ?: requireContext()
        chatAdapter = ChatAdapter(pluginContext, markwon) { action, message ->
            onMessageAction(action, message)
        }
        messagesRecyclerView = RecyclerView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
        mainContainer.addView(messagesRecyclerView)

        // Input container
        val inputContainer = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        inputEditText = EditText(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            hint = "Enter your message..."
            setPadding(8, 8, 8, 8)
        }
        inputContainer.addView(inputEditText)

        sendButton = Button(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Send"
            isEnabled = false
            setOnClickListener { onSendClicked() }
        }
        inputContainer.addView(sendButton)

        mainContainer.addView(inputContainer)
        rootView.addView(mainContainer)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                chatAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.agentState.collect { state ->
                updateStatusUI(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isBackendAvailable.collect { isAvailable ->
                sendButton.isEnabled = isAvailable && viewModel.agentState.value is AgentState.Idle
                if (!isAvailable) {
                    statusTextView.text = "No LLM backend available. Configure AI Core plugin."
                    statusTextView.visibility = View.VISIBLE
                } else {
                    statusTextView.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingApprovalRequest.collect { request ->
                if (request != null) {
                    showApprovalDialog(request)
                }
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
        updateContextVisibility()
        viewModel.setContextFiles(contextFiles)
    }

    private fun addChipForFile(file: File) {
        val chip = Chip(requireContext()).apply {
            text = file.name
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                contextFiles.remove(file)
                chipGroup.removeView(this)
                updateContextVisibility()
                viewModel.setContextFiles(contextFiles)
            }
        }
        chipGroup.addView(chip)
    }

    private fun updateContextVisibility() {
        val contextContainer = chipGroup.parent as? LinearLayout
        contextContainer?.visibility = if (contextFiles.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateStatusUI(state: AgentState) {
        when (state) {
            is AgentState.Idle -> {
                statusTextView.visibility = View.GONE
                sendButton.isEnabled = viewModel.isBackendAvailable.value
            }
            is AgentState.Initializing -> {
                statusTextView.text = state.message
                statusTextView.visibility = View.VISIBLE
                sendButton.isEnabled = false
            }
            is AgentState.Thinking -> {
                statusTextView.text = state.thought
                statusTextView.visibility = View.VISIBLE
                sendButton.isEnabled = false
            }
            is AgentState.Executing -> {
                statusTextView.text = "Step ${state.currentStepIndex + 1} of ${state.totalSteps}: ${state.description}"
                statusTextView.visibility = View.VISIBLE
                sendButton.isEnabled = false
            }
            is AgentState.Processing -> {
                statusTextView.visibility = View.GONE
                sendButton.isEnabled = false
            }
            is AgentState.Cancelling -> {
                statusTextView.visibility = View.GONE
                sendButton.isEnabled = false
            }
            is AgentState.Error -> {
                // Errors are now shown as messages in the list, not in status bar
                statusTextView.visibility = View.GONE
                sendButton.isEnabled = viewModel.isBackendAvailable.value
            }
        }
    }

    private fun onSendClicked() {
        val message = inputEditText.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a message", Toast.LENGTH_SHORT).show()
            return
        }
        inputEditText.text.clear()
        viewModel.sendMessage(message)
    }

    private fun onClearClicked() {
        viewModel.clearMessages()
        Toast.makeText(requireContext(), "Chat cleared", Toast.LENGTH_SHORT).show()
    }

    private fun onMessageAction(action: String, message: com.itsaky.androidide.plugins.aiassistant.models.ChatMessage) {
        when (action) {
            ChatAdapter.ACTION_EDIT -> {
                // Show dialog to edit message
                inputEditText.setText(message.text)
                inputEditText.requestFocus()
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

    private fun showSettingsMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Clear chat")
        popup.menu.add(0, 2, 0, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    onClearClicked()
                    true
                }
                2 -> {
                    openSettingsFragment()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openSettingsFragment() {
        val settingsFragment = AiSettingsFragment()
        // Show as dialog fragment to avoid overlapping with chat UI
        settingsFragment.show(parentFragmentManager, "ai_settings")
    }


    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stopProcessing()
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
