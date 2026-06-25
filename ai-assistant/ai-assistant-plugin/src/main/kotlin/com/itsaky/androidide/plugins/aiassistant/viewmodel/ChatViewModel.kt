package com.itsaky.androidide.plugins.aiassistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.models.ChatMessage
import com.itsaky.androidide.plugins.aiassistant.models.MessageStatus
import com.itsaky.androidide.plugins.aiassistant.models.Sender
import com.itsaky.androidide.plugins.aiassistant.tool.Executor
import com.itsaky.androidide.plugins.aiassistant.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aiassistant.tool.ToolCall
import com.itsaky.androidide.plugins.aiassistant.tool.ToolRouter
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.CreateFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ListFilesHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.SearchProjectHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.UpdateFileHandler
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                UpdateFileHandler(context)
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
     * Parse tool calls from text.
     * Looks for JSON objects with tool call structure.
     */
    private fun parseToolCalls(text: String): List<ToolCall> {
        val toolCalls = mutableListOf<ToolCall>()

        // Look for tool call patterns: {"tool":"name","args":{...}}
        val toolCallRegex = Regex("""<tool_call>\s*(\{[^}]+\})\s*</tool_call>""")
        val matches = toolCallRegex.findAll(text)

        for (match in matches) {
            try {
                val jsonStr = match.groupValues[1]
                val json = JSONObject(jsonStr)
                val toolName = json.optString("tool") ?: json.optString("name") ?: continue
                val argsJson = json.optJSONObject("args") ?: json.optJSONObject("arguments") ?: JSONObject()

                val args = mutableMapOf<String, Any?>()
                val keys = argsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    args[key] = argsJson.get(key)
                }

                toolCalls.add(ToolCall(toolName, args))
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error parsing tool call: ${e.message}")
            }
        }

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

                // Add empty agent message that will be updated with streaming tokens
                val agentMessageId = UUID.randomUUID().toString()
                val agentMessage = ChatMessage(
                    id = agentMessageId,
                    text = "",
                    sender = Sender.AGENT,
                    status = MessageStatus.SENT  // SENT so text is visible immediately
                )
                _messages.value = _messages.value + agentMessage

                // Set processing state
                _agentState.value = AgentState.Processing("Generating...")

                // Record start time
                val startTime = System.currentTimeMillis()

                // Create LLM config
                val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                    temperature = 0.7f
                    maxTokens = 2048
                    systemPrompt = "You are a helpful coding assistant integrated into AndroidIDE."
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
                            _messages.value = _messages.value.map { msg ->
                                if (msg.id == agentMessageId) {
                                    msg.copy(text = responseBuilder.toString())
                                } else {
                                    msg
                                }
                            }
                        }
                    }

                    override fun onComplete(response: LlmInferenceService.LlmResponse) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val durationMs = System.currentTimeMillis() - startTime
                            val finalText = responseBuilder.toString()

                            // Mark message as completed with final text
                            launch(Dispatchers.Main) {
                                _messages.value = _messages.value.map { msg ->
                                    if (msg.id == agentMessageId) {
                                        msg.copy(
                                            text = finalText,
                                            status = MessageStatus.COMPLETED,
                                            durationMs = durationMs
                                        )
                                    } else {
                                        msg
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
