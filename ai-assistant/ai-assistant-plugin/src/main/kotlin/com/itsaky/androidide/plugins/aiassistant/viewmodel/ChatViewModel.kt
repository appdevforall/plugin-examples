package com.itsaky.androidide.plugins.aiassistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.models.ChatMessage
import com.itsaky.androidide.plugins.aiassistant.models.ChatSession
import com.itsaky.androidide.plugins.aiassistant.models.MessageStatus
import com.itsaky.androidide.plugins.aiassistant.models.Sender
import com.itsaky.androidide.plugins.aiassistant.tool.Executor
import com.itsaky.androidide.plugins.aiassistant.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aiassistant.tool.ToolCall
import com.itsaky.androidide.plugins.aiassistant.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aiassistant.tool.ToolRouter
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.CreateFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ListFilesHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.SearchProjectHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.UpdateFileHandler
import com.itsaky.androidide.plugins.aiassistant.data.ChatStorageManager
import com.itsaky.androidide.plugins.aiassistant.utils.ToolExecutionTracker
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * ViewModel for managing chat state and LLM interactions.
 */
class ChatViewModel(
    private val getContext: () -> PluginContext?
) : ViewModel() {

    private fun getLlmService(): LlmInferenceService? {
        return try {
            SharedServices.get(LlmInferenceService::class.java)
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error getting LLM service", e)
            null
        }
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _isBackendAvailable = MutableStateFlow(false)
    val isBackendAvailable: StateFlow<Boolean> = _isBackendAvailable.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    val currentSession: StateFlow<ChatSession?> = combine(_sessions, _currentSessionId) { sessions, id ->
        sessions.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private var currentBackendId: String = "local" // Default to local backend

    // Tool execution infrastructure
    private val approvalManager = ToolApprovalManager()
    private val toolRouter: ToolRouter
    private val executor: Executor
    val toolExecutionTracker = ToolExecutionTracker()

    private val _pendingApprovalRequest = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApprovalRequest: StateFlow<ApprovalRequest?> = _pendingApprovalRequest.asStateFlow()

    private var contextFiles = listOf<File>()

    private var stateUpdateJob: Job? = null

    private lateinit var storageManager: ChatStorageManager

    fun isStorageInitialized(): Boolean = ::storageManager.isInitialized

    init {
        // Initialize tool handlers
        val context = getContext()
        val handlers = if (context != null) {
            listOf(
                ReadFileHandler(context),
                ListFilesHandler(context),
                SearchProjectHandler(context),
                CreateFileHandler(context),
                UpdateFileHandler(context),
                com.itsaky.androidide.plugins.aiassistant.tool.handlers.RunAppHandler(context)
            )
        } else {
            emptyList()
        }

        toolRouter = ToolRouter(handlers)
        executor = Executor(toolRouter, approvalManager, toolExecutionTracker)

        // Monitor approval requests
        viewModelScope.launch {
            while (true) {
                delay(100) // Poll every 100ms
                val request = approvalManager.getCurrentApprovalRequest()
                if (request != _pendingApprovalRequest.value) {
                    _pendingApprovalRequest.value = request
                }
            }
        }
    }

    fun initializeStorage(context: android.content.Context) {
        android.util.Log.d("ChatViewModel", "initializeStorage called")
        storageManager = ChatStorageManager(context)
        loadSessions()
    }

    fun loadSessions() {
        val loaded = storageManager.loadSessions()
        android.util.Log.d("ChatViewModel", "loadSessions: loaded ${loaded.size} sessions")
        if (loaded.isEmpty()) {
            android.util.Log.d("ChatViewModel", "No sessions found, creating new session")
            createNewSession()
        } else {
            _sessions.value = loaded
            val currentId = storageManager.loadCurrentSessionId()
            val session = loaded.firstOrNull { it.id == currentId } ?: loaded.first()
            android.util.Log.d("ChatViewModel", "Switching to session ${session.id} with ${session.messages.size} messages")
            switchToSession(session.id)
        }
    }

    private fun persistSessions() {
        storageManager.saveSessions(_sessions.value)
        storageManager.saveCurrentSessionId(_currentSessionId.value)
    }

    /**
     * Submit user's approval decision.
     */
    fun submitApproval(result: ApprovalResult) {
        approvalManager.submitApproval(result)
        _pendingApprovalRequest.value = null
    }

    /**
     * Set context files to include in prompts.
     */
    fun setContextFiles(files: List<File>) {
        contextFiles = files
    }

    /**
     * Helper method to synchronize a message to the current session.
     * Updates or adds the message to the session's message list.
     */
    private fun syncMessageToSession(message: ChatMessage) {
        _currentSessionId.value?.let { sessionId ->
            val session = _sessions.value.firstOrNull { it.id == sessionId }
            if (session != null) {
                val existingIndex = session.messages.indexOfFirst { it.id == message.id }
                if (existingIndex >= 0) {
                    session.messages[existingIndex] = message
                } else {
                    session.messages.add(message)
                }
            }
        }
    }

    /**
     * Build context string from selected files.
     */
    private fun buildContextString(): String {
        if (contextFiles.isEmpty()) return ""

        val contextBuilder = StringBuilder()
        contextBuilder.append("\n\nCONTEXT FILES:\n\n")

        contextFiles.forEach { file ->
            if (file.exists() && file.isFile) {
                try {
                    val content = file.readText()
                    contextBuilder.append("=== ${file.name} ===\n")
                    contextBuilder.append(content)
                    contextBuilder.append("\n\n")
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "Error reading context file ${file.name}: ${e.message}")
                }
            }
        }

        return contextBuilder.toString()
    }

    /**
     * Build system prompt with tool descriptions.
     */
    private fun buildSystemPrompt(): String {
        val toolDescriptions = toolRouter.getAllHandlers().joinToString("\n") { handler ->
            "- ${handler.toolName}: ${handler.description}"
        }

        val prompt = """
        You are a helpful coding assistant integrated into AndroidIDE.

        CRITICAL: You MUST use tools for ANY action-related request. Do NOT just describe what you would do.

        AVAILABLE TOOLS:
        $toolDescriptions

        TOOL CALLING RULES:
        1. When user asks to perform an action (list, read, search, create, update, run), IMMEDIATELY call the tool
        2. Do NOT explain or apologize - just execute the tool call
        3. Always provide the tool call in this EXACT format:
           <tool_call>{"tool":"TOOL_NAME","args":{"param1":"value1"}}</tool_call>
        4. Execute tools BEFORE saying anything else

        EXAMPLES (you MUST follow this exact pattern):

        User: "list files in src"
        <tool_call>{"tool":"list_files","args":{"directory":"src"}}</tool_call>

        User: "read MainActivity.kt"
        <tool_call>{"tool":"read_file","args":{"path":"MainActivity.kt"}}</tool_call>

        User: "search for onCreate"
        <tool_call>{"tool":"search_project","args":{"query":"onCreate"}}</tool_call>

        User: "run the app"
        <tool_call>{"tool":"run_app","args":{}}</tool_call>

        MULTI-TOOL EXAMPLE:
        User: "list files and search for test"
        <tool_call>{"tool":"list_files","args":{"directory":"."}}</tool_call>
        <tool_call>{"tool":"search_project","args":{"query":"test"}}</tool_call>

        INSTRUCTIONS:
        - ALWAYS output tool calls FIRST, before any explanatory text
        - Use exact tool names from the list above
        - Include all required parameters
        - If user wants multiple actions, call multiple tools
        - Do NOT skip or delay tool calls
        """.trimIndent()

        android.util.Log.d("ChatViewModel", "System prompt built with ${toolRouter.getAllHandlers().size} tools")
        return prompt
    }

    /**
     * Parse tool calls from text using multi-strategy extraction.
     * Tries: XML tags → JSON objects → Implicit actions
     */
    private fun parseToolCalls(text: String): List<ToolCall> {
        return ToolCallExtractor.extractToolCalls(text)
    }

    /**
     * Execute tool calls and add results to chat.
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>) {
        if (toolCalls.isEmpty()) return

        val executingState = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = toolCalls.size,
            description = toolCalls.first().name
        )
        _agentState.value = executingState
        startStateTimer(executingState)

        val results = executor.execute(toolCalls)

        // Add tool results as messages
        results.forEachIndexed { index, result ->
            val toolCall = toolCalls[index]
            val resultText = if (result.success) {
                "${toolCall.name}: ${result.message}\n${result.data ?: ""}"
            } else {
                "${toolCall.name} failed: ${result.message}\n${result.error_details ?: ""}"
            }
            val resultMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = resultText,
                sender = Sender.TOOL,
                status = if (result.success) MessageStatus.SENT else MessageStatus.ERROR
            )
            android.util.Log.d("ChatViewModel", "Adding tool result message: $resultText")
            _messages.value = _messages.value + resultMessage
            android.util.Log.d("ChatViewModel", "Total messages after tool result: ${_messages.value.size}")
            syncMessageToSession(resultMessage)
        }

        stopStateTimer()
        _agentState.value = AgentState.Idle
    }

    /**
     * Check if any LLM backend is available.
     * Should be called when the fragment becomes visible.
     * Retries with delays to handle plugin loading timing.
     */
    fun checkBackendAvailability() {
        android.util.Log.d("ChatViewModel", "checkBackendAvailability: Starting check")
        viewModelScope.launch(Dispatchers.IO) {
            // Retry up to 5 times with 500ms delays to handle plugin loading order
            repeat(5) { attempt ->
                android.util.Log.d("ChatViewModel", "checkBackendAvailability: Attempt ${attempt + 1}")
                val llmService = getLlmService()
                android.util.Log.d("ChatViewModel", "checkBackendAvailability: llmService = $llmService")
                if (llmService != null) {
                    try {
                        val backends = llmService.availableBackends
                        android.util.Log.d("ChatViewModel", "checkBackendAvailability: Found ${backends.size} backends")

                        // Read backend preference from settings
                        val prefs = getContext()?.getPluginSharedPreferences("AgentSettings")
                        val preferredBackendName = prefs?.getString("ai_backend_preference", "LOCAL_LLM")
                        android.util.Log.d("ChatViewModel", "checkBackendAvailability: Preferred backend = $preferredBackendName")
                        val preferredBackendId = when (preferredBackendName) {
                            "GEMINI" -> "gemini"
                            "LOCAL_LLM" -> "local"
                            else -> "local"
                        }

                        // First try to use the preferred backend
                        var foundAvailable = false
                        val preferredBackend = backends.find { it.id == preferredBackendId }
                        android.util.Log.d("ChatViewModel", "checkBackendAvailability: Preferred backend (${preferredBackendId}) found=${preferredBackend != null}, available=${preferredBackend?.isAvailable}")
                        if (preferredBackend != null && preferredBackend.isAvailable) {
                            _isBackendAvailable.value = true
                            currentBackendId = preferredBackend.id
                            android.util.Log.d("ChatViewModel", "checkBackendAvailability: Using preferred backend ${preferredBackend.id}")
                            return@launch // Success, exit retry loop
                        }

                        // If preferred backend not available, try any available backend as fallback
                        for (backend in backends) {
                            android.util.Log.d("ChatViewModel", "checkBackendAvailability: Checking backend ${backend.id}, available=${backend.isAvailable}")
                            if (backend.isAvailable) {
                                _isBackendAvailable.value = true
                                currentBackendId = backend.id
                                foundAvailable = true
                                android.util.Log.d("ChatViewModel", "checkBackendAvailability: Using fallback backend ${backend.id}")
                                break
                            }
                        }

                        if (foundAvailable) {
                            return@launch // Success, exit retry loop
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatViewModel", "Error checking backends on attempt ${attempt + 1}: ${e.message}", e)
                    }
                }

                // Wait before next retry (except on last attempt)
                if (attempt < 4) {
                    delay(500)
                }
            }

            // All retries failed
            android.util.Log.d("ChatViewModel", "checkBackendAvailability: All retries failed, no backend available")
            _isBackendAvailable.value = false
        }
    }

    /**
     * Send a user message and get agent response.
     */
    fun sendMessage(userMessage: String) {
        android.util.Log.d("ChatViewModel", "sendMessage called with: '$userMessage'")
        val llmService = getLlmService()
        if (llmService == null) {
            android.util.Log.d("ChatViewModel", "sendMessage: LLM service not available")
            _agentState.value = AgentState.Error("LLM service not available. Install AI Core plugin.")
            return
        }

        if (!_isBackendAvailable.value) {
            android.util.Log.d("ChatViewModel", "sendMessage: Backend not available")
            _agentState.value = AgentState.Error("No LLM backend available. Please configure one in AI Core plugin.")
            return
        }

        if (userMessage.isBlank()) {
            android.util.Log.d("ChatViewModel", "sendMessage: Message is blank")
            return
        }

        android.util.Log.d("ChatViewModel", "sendMessage: Starting message processing")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Add user message
                val userChatMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = userMessage,
                    sender = Sender.USER,
                    status = MessageStatus.SENT
                )
                _messages.value = _messages.value + userChatMessage
                android.util.Log.d("ChatViewModel", "sendMessage: Added user message, total messages=${_messages.value.size}")
                syncMessageToSession(userChatMessage)

                // Add empty agent message that will be updated with streaming tokens
                val agentMessageId = UUID.randomUUID().toString()
                val agentMessage = ChatMessage(
                    id = agentMessageId,
                    text = "",
                    sender = Sender.AGENT,
                    status = MessageStatus.SENT  // SENT so text is visible immediately
                )
                _messages.value = _messages.value + agentMessage
                syncMessageToSession(agentMessage)

                // Set processing state
                _agentState.value = AgentState.Processing("Generating...")

                // Record start time
                val startTime = System.currentTimeMillis()

                // Create LLM config
                val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                    temperature = 0.7f
                    maxTokens = 4096  // Increased from 2048 to ensure complete tool calls are generated
                    systemPrompt = buildSystemPrompt()
                }

                // Build message with context if any files are selected
                val messageWithContext = buildString {
                    append(userMessage)
                    val context = buildContextString()
                    if (context.isNotEmpty()) {
                        append(context)
                    }
                }

                // Accumulated response text
                val responseBuilder = StringBuilder()

                // Use streaming API with callback
                llmService.generateStreaming(messageWithContext, config, object : LlmInferenceService.StreamCallback {
                    override fun onToken(token: String) {
                        viewModelScope.launch(Dispatchers.Main) {
                            // Accumulate token
                            responseBuilder.append(token)

                            // Update the message with new text using map() to trigger DiffUtil
                            val updatedMessage = ChatMessage(
                                id = agentMessageId,
                                text = responseBuilder.toString(),
                                sender = Sender.AGENT,
                                status = MessageStatus.SENT
                            )
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == agentMessageId) {
                                    updatedMessage
                                } else {
                                    msg
                                }
                            }

                            // Also update current session's message
                            syncMessageToSession(updatedMessage)
                        }
                    }

                    override fun onComplete(response: LlmInferenceService.LlmResponse) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val durationMs = System.currentTimeMillis() - startTime
                            val finalText = responseBuilder.toString()

                            // Mark message as completed with final text
                            launch(Dispatchers.Main) {
                                val updatedMessage = ChatMessage(
                                    id = agentMessageId,
                                    text = finalText,
                                    sender = Sender.AGENT,
                                    status = MessageStatus.COMPLETED,
                                    durationMs = durationMs
                                )
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        updatedMessage
                                    } else {
                                        msg
                                    }
                                }

                                syncMessageToSession(updatedMessage)
                            }

                            // Parse and execute tool calls if any
                            val toolCalls = parseToolCalls(finalText)
                            if (toolCalls.isNotEmpty()) {
                                executeToolCalls(toolCalls)
                            } else {
                                launch(Dispatchers.Main) {
                                    _agentState.value = AgentState.Idle
                                }
                            }
                        }
                    }

                    override fun onError(error: String) {
                        viewModelScope.launch(Dispatchers.Main) {
                            val durationMs = System.currentTimeMillis() - startTime

                            // Remove the agent message
                            _messages.value = _messages.value.filter { it.id != agentMessageId }

                            // Add error message
                            _agentState.value = AgentState.Error(error)
                            val errorMessage = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                text = error,
                                sender = Sender.SYSTEM,
                                status = MessageStatus.ERROR,
                                durationMs = durationMs
                            )
                            _messages.value = _messages.value + errorMessage
                            syncMessageToSession(errorMessage)
                        }
                    }
                })

            } catch (e: Exception) {
                _agentState.value = AgentState.Error("Error: ${e.message}")
                val errorMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = "Error: ${e.message}",
                    sender = Sender.SYSTEM,
                    status = MessageStatus.ERROR
                )
                _messages.value = _messages.value + errorMessage
            }
        }
    }

    /**
     * Clear all messages from the conversation.
     */
    fun clearMessages() {
        _messages.value = emptyList()
        _agentState.value = AgentState.Idle
    }

    /**
     * Create a new chat session.
     */
    fun createNewSession() {
        val newSession = ChatSession()
        _sessions.value = _sessions.value + newSession
        _currentSessionId.value = newSession.id
        _messages.value = emptyList()
    }

    /**
     * Switch to an existing chat session.
     */
    fun switchToSession(sessionId: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId }
        android.util.Log.d("ChatViewModel", "switchToSession: sessionId=$sessionId, session found=${session != null}")
        if (session != null) {
            _currentSessionId.value = sessionId
            _messages.value = session.messages
            android.util.Log.d("ChatViewModel", "switchToSession: set _messages to ${session.messages.size} messages")
        }
    }

    /**
     * Delete a chat session.
     */
    fun deleteSession(sessionId: String) {
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        if (_currentSessionId.value == sessionId) {
            val remaining = _sessions.value.firstOrNull()
            _currentSessionId.value = remaining?.id
            _messages.value = remaining?.messages ?: emptyList()
        }
    }

    /**
     * Stop any ongoing processing.
     */
    fun stopProcessing() {
        if (_agentState.value is AgentState.Processing) {
            _agentState.value = AgentState.Cancelling
            getLlmService()?.cancelGeneration()
            _agentState.value = AgentState.Idle
        }
    }

    /**
     * Start a timer that updates the executing state with elapsed time.
     * Updates every 100ms for smooth progress display.
     */
    fun startStateTimer(state: AgentState.Executing) {
        stateUpdateJob?.cancel()
        stateUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(100) // Update every 100ms
                val elapsed = System.currentTimeMillis() - state.startTime
                _agentState.value = state.copy(elapsedMillis = elapsed)
            }
        }
    }

    /**
     * Stop the state timer.
     */
    fun stopStateTimer() {
        stateUpdateJob?.cancel()
        stateUpdateJob = null
    }

    override fun onCleared() {
        super.onCleared()
        persistSessions()
        stopProcessing()
        stopStateTimer()
    }
}
