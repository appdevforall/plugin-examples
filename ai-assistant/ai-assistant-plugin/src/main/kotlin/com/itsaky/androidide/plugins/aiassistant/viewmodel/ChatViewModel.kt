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
import com.itsaky.androidide.plugins.aiassistant.tool.ToolRouter
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.AddDependencyHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.AddStringResourceHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.CreateFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.GetBuildOutputHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ListFilesHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.RunAppHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.SearchProjectHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.UpdateFileHandler
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
            // Get service from SharedServices (global registry)
            val service = SharedServices.get(LlmInferenceService::class.java)
            android.util.Log.d("ChatViewModel", "SharedServices.get returned: ${service != null}")
            service
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

    private val _pendingApprovalRequest = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApprovalRequest: StateFlow<ApprovalRequest?> = _pendingApprovalRequest.asStateFlow()

    private var contextFiles = listOf<File>()

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
                RunAppHandler(context),
                GetBuildOutputHandler(context),
                AddDependencyHandler(context),
                AddStringResourceHandler(context)
            )
        } else {
            emptyList()
        }

        toolRouter = ToolRouter(handlers)
        executor = Executor(toolRouter, approvalManager)

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
        val tools = toolRouter.getAvailableTools()
        val toolDescriptions = tools.mapNotNull { toolName ->
            val handler = toolRouter.getHandler(toolName)
            if (handler != null) {
                "- ${handler.toolName}: ${handler.description}"
            } else {
                null
            }
        }.joinToString("\n")

        return buildString {
            append("You are a helpful coding assistant integrated into AndroidIDE.\n\n")

            if (toolDescriptions.isNotEmpty()) {
                append("AVAILABLE TOOLS:\n")
                append("You have access to the following tools. To use a tool, wrap the call in <tool_call> tags with JSON:\n")
                append("<tool_call>{\"tool\":\"tool_name\",\"args\":{\"arg1\":\"value1\"}}</tool_call>\n\n")
                append(toolDescriptions)
                append("\n\n")
            }

            append("IMPORTANT:\n")
            append("- Use tools to perform actions (build, read files, search, etc.) instead of just explaining how to do them\n")
            append("- When a user asks you to do something, USE THE TOOLS to actually do it\n")
            append("- Format tool calls correctly with the <tool_call> tags and JSON structure\n")
            append("- You can make multiple tool calls in a single response if needed\n")
            append("- After tool execution, you will see the results and can respond to the user\n")
        }
    }

    /**
     * Parse tool calls from text.
     * Looks for JSON objects with tool call structure.
     */
    private fun parseToolCalls(text: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        android.util.Log.d("ChatViewModel", "=== PARSING TOOL CALLS === Text length: ${text.length}")

        // Look for tool call patterns: <tool_call>{"tool":"name","args":{...}}</tool_call>
        // Use lazy matching to get content between tags
        val toolCallRegex = Regex("""<tool_call>\s*(.+?)\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        val matches = toolCallRegex.findAll(text)

        for (match in matches) {
            try {
                val jsonStr = match.groupValues[1].trim()
                android.util.Log.d("ChatViewModel", "=== FOUND TOOL CALL === JSON: $jsonStr")

                val json = JSONObject(jsonStr)
                val toolName = json.optString("tool") ?: json.optString("name") ?: continue
                val argsJson = json.optJSONObject("args") ?: json.optJSONObject("arguments") ?: JSONObject()

                val args = mutableMapOf<String, Any?>()
                val keys = argsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    args[key] = argsJson.get(key)
                }

                android.util.Log.d("ChatViewModel", "=== PARSED TOOL === name: $toolName, args: $args")
                toolCalls.add(ToolCall(toolName, args))
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error parsing tool call: ${e.message}", e)
            }
        }

        android.util.Log.d("ChatViewModel", "=== TOTAL TOOL CALLS PARSED === ${toolCalls.size}")
        return toolCalls
    }

    /**
     * Execute tool calls and add results to chat.
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>) {
        if (toolCalls.isEmpty()) return

        _agentState.value = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = toolCalls.size,
            description = toolCalls.first().name
        )

        val results = executor.execute(toolCalls)

        // Add tool results as messages
        results.forEachIndexed { index, result ->
            val toolCall = toolCalls[index]
            val resultMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = if (result.success) {
                    "${toolCall.name}: ${result.message}\n${result.data ?: ""}"
                } else {
                    "${toolCall.name} failed: ${result.message}\n${result.error_details ?: ""}"
                },
                sender = Sender.TOOL,
                status = if (result.success) MessageStatus.SENT else MessageStatus.ERROR
            )
            _messages.value = _messages.value + resultMessage

            // Also add to current session
            _currentSessionId.value?.let { sessionId ->
                val session = _sessions.value.firstOrNull { it.id == sessionId }
                if (session != null) {
                    session.messages.add(resultMessage)
                }
            }
        }

        _agentState.value = AgentState.Idle
    }

    /**
     * Check if any LLM backend is available.
     * Should be called when the fragment becomes visible.
     * Retries with delays to handle plugin loading timing.
     */
    fun checkBackendAvailability() {
        android.util.Log.e("ChatViewModel", "=== FUNCTION START === checkBackendAvailability() [BUILD:20260623-1305-FINAL]")
        android.util.Log.e("ChatViewModel", "=== THIS IS THE NEW FIXED CODE ===")
        viewModelScope.launch(Dispatchers.IO) {
            android.util.Log.e("ChatViewModel", "=== INSIDE COROUTINE === viewModelScope.launch started")
            // Retry up to 5 times with 500ms delays to handle plugin loading order
            repeat(5) { attempt ->
                android.util.Log.e("ChatViewModel", "=== LOOP ITERATION === Attempt ${attempt + 1}/5")
                val llmService = getLlmService()
                android.util.Log.e("ChatViewModel", "=== SERVICE CHECK === llmService is ${if (llmService != null) "NOT NULL" else "NULL"}")
                if (llmService != null) {
                    try {
                        val backends = llmService.availableBackends
                        android.util.Log.e("ChatViewModel", "=== BACKENDS LIST === Found ${backends.size} backends")

                        // Read backend preference from settings
                        val prefs = getContext()?.getPluginSharedPreferences("AgentSettings")
                        val preferredBackendName = prefs?.getString("ai_backend_preference", "LOCAL_LLM")
                        val preferredBackendId = when (preferredBackendName) {
                            "GEMINI" -> "gemini"
                            "LOCAL_LLM" -> "local"
                            else -> "local"
                        }
                        android.util.Log.e("ChatViewModel", "=== PREFERENCE === Preferred backend from settings: $preferredBackendName -> $preferredBackendId")

                        // First try to use the preferred backend
                        var foundAvailable = false
                        val preferredBackend = backends.find { it.id == preferredBackendId }
                        if (preferredBackend != null) {
                            val backendAvailable = preferredBackend.isAvailable
                            android.util.Log.e("ChatViewModel", "=== CHECKING PREFERRED === Backend: ${preferredBackend.id} - ${preferredBackend.name}, isAvailable: $backendAvailable")
                            if (backendAvailable) {
                                android.util.Log.e("ChatViewModel", "=== USING PREFERRED === Setting backend to: ${preferredBackend.id}")
                                _isBackendAvailable.value = true
                                currentBackendId = preferredBackend.id
                                android.util.Log.e("ChatViewModel", "=== SUCCESS === Using preferred backend: $currentBackendId")
                                foundAvailable = true
                            } else {
                                android.util.Log.e("ChatViewModel", "=== PREFERRED NOT AVAILABLE === Backend ${preferredBackend.id} is not available")
                            }
                        } else {
                            android.util.Log.e("ChatViewModel", "=== PREFERRED NOT FOUND === Backend $preferredBackendId not registered")
                        }

                        // If preferred backend not available, try any available backend as fallback
                        if (!foundAvailable) {
                            android.util.Log.e("ChatViewModel", "=== FALLBACK === Trying any available backend")
                            for (backend in backends) {
                                val backendAvailable = backend.isAvailable
                                android.util.Log.e("ChatViewModel", "=== CHECKING FALLBACK === Backend: ${backend.id} - ${backend.name}, isAvailable: $backendAvailable")
                                if (backendAvailable) {
                                    android.util.Log.e("ChatViewModel", "=== FALLBACK FOUND === Setting backend to: ${backend.id}")
                                    _isBackendAvailable.value = true
                                    currentBackendId = backend.id
                                    android.util.Log.e("ChatViewModel", "=== SUCCESS === Using fallback backend: $currentBackendId")
                                    foundAvailable = true
                                    break
                                }
                            }
                        }

                        if (foundAvailable) {
                            android.util.Log.e("ChatViewModel", "=== EARLY EXIT === Found available backend, exiting retry loop")
                            return@launch // Success, exit retry loop
                        } else {
                            android.util.Log.e("ChatViewModel", "=== NO BACKENDS === No available backends found (not configured)")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatViewModel", "=== EXCEPTION === Error checking backends on attempt ${attempt + 1}: ${e.message}", e)
                    }
                } else {
                    android.util.Log.e("ChatViewModel", "=== NULL SERVICE === LLM service is null on attempt ${attempt + 1}")
                }

                // Wait before next retry (except on last attempt)
                if (attempt < 4) {
                    android.util.Log.e("ChatViewModel", "=== WAITING === Delaying 500ms before next retry...")
                    delay(500)
                } else {
                    android.util.Log.e("ChatViewModel", "=== FINAL ATTEMPT === This was the last attempt")
                }
            }

            // All retries failed
            android.util.Log.e("ChatViewModel", "=== ALL RETRIES FAILED === Setting backend available to false")
            _isBackendAvailable.value = false
        }
    }

    /**
     * Send a user message and get agent response.
     */
    fun sendMessage(userMessage: String) {
        val llmService = getLlmService()
        if (llmService == null) {
            _agentState.value = AgentState.Error("LLM service not available. Install AI Core plugin.")
            return
        }

        if (!_isBackendAvailable.value) {
            _agentState.value = AgentState.Error("No LLM backend available. Please configure one in AI Core plugin.")
            return
        }

        if (userMessage.isBlank()) {
            return
        }

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

                // Update current session with the new message
                _currentSessionId.value?.let { sessionId ->
                    val session = _sessions.value.firstOrNull { it.id == sessionId }
                    if (session != null) {
                        session.messages.add(userChatMessage)
                    }
                }

                // Add empty agent message that will be updated with streaming tokens
                val agentMessageId = UUID.randomUUID().toString()
                val agentMessage = ChatMessage(
                    id = agentMessageId,
                    text = "",
                    sender = Sender.AGENT,
                    status = MessageStatus.SENT  // SENT so text is visible immediately
                )
                _messages.value = _messages.value + agentMessage

                // Add agent message to current session as well
                _currentSessionId.value?.let { sessionId ->
                    val session = _sessions.value.firstOrNull { it.id == sessionId }
                    if (session != null) {
                        session.messages.add(agentMessage)
                    }
                }

                // Set processing state
                _agentState.value = AgentState.Processing("Generating...")

                // Record start time
                val startTime = System.currentTimeMillis()

                // Create LLM config
                val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                    temperature = 0.7f
                    maxTokens = 2048
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
                        android.util.Log.d("ChatViewModel", "=== STREAM CALLBACK === onToken called with ${token.length} chars")
                        viewModelScope.launch(Dispatchers.Main) {
                            // Accumulate token
                            responseBuilder.append(token)
                            android.util.Log.d("ChatViewModel", "=== ACCUMULATED === Total response length: ${responseBuilder.length}")

                            // Update the message with new text using map() to trigger DiffUtil
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == agentMessageId) {
                                    android.util.Log.d("ChatViewModel", "=== UPDATING MESSAGE === Setting text to ${responseBuilder.length} chars")
                                    msg.copy(text = responseBuilder.toString())
                                } else {
                                    msg
                                }
                            }

                            // Also update current session's message
                            _currentSessionId.value?.let { sessionId ->
                                val session = _sessions.value.firstOrNull { it.id == sessionId }
                                session?.messages?.firstOrNull { it.id == agentMessageId }?.let { msg ->
                                    msg.copy(text = responseBuilder.toString())
                                }
                            }

                            android.util.Log.d("ChatViewModel", "=== MESSAGES UPDATED === New messages count: ${_messages.value.size}")
                        }
                    }

                    override fun onComplete(response: LlmInferenceService.LlmResponse) {
                        android.util.Log.d("ChatViewModel", "=== STREAM CALLBACK === onComplete called, success: ${response.success}")
                        android.util.Log.d("ChatViewModel", "=== FINAL TEXT === Length: ${responseBuilder.length}")
                        viewModelScope.launch(Dispatchers.IO) {
                            val durationMs = System.currentTimeMillis() - startTime
                            val finalText = responseBuilder.toString()
                            android.util.Log.d("ChatViewModel", "=== COMPLETION === Final text: $finalText")

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

                                // Also update current session's message
                                _currentSessionId.value?.let { sessionId ->
                                    val session = _sessions.value.firstOrNull { it.id == sessionId }
                                    val messageIndex = session?.messages?.indexOfFirst { it.id == agentMessageId } ?: -1
                                    if (messageIndex >= 0) {
                                        session?.messages?.set(messageIndex, updatedMessage)
                                    }
                                }
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
                        android.util.Log.e("ChatViewModel", "=== STREAM CALLBACK === onError called: $error")
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
        if (session != null) {
            _currentSessionId.value = sessionId
            _messages.value = session.messages
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

    override fun onCleared() {
        super.onCleared()
        stopProcessing()
    }
}
