package com.itsaky.androidide.plugins.aiassistant.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.models.AgentState
import com.itsaky.androidide.plugins.aiassistant.models.ChatMessage
import com.itsaky.androidide.plugins.aiassistant.models.ChatSession
import com.itsaky.androidide.plugins.aiassistant.models.MessageStatus
import com.itsaky.androidide.plugins.aiassistant.models.Sender
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.AgentLoop
import com.itsaky.androidide.plugins.aiassistant.tool.Executor
import com.itsaky.androidide.plugins.aiassistant.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aiassistant.tool.ToolCall
import com.itsaky.androidide.plugins.aiassistant.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aiassistant.tool.ToolRouter
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.AddDependencyHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.CreateFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.GenerateFromTemplateHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.GradleSyncHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ListFilesHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.OpenFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadBuildOutputHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.SearchProjectHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.UpdateFileHandler
import com.itsaky.androidide.plugins.aiassistant.data.ChatStorageManager
import com.itsaky.androidide.plugins.aiassistant.utils.ToolExecutionTracker
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * ViewModel for managing chat state and LLM interactions.
 */
class ChatViewModel(
    private val getContext: () -> PluginContext?
) : ViewModel() {

    companion object {
        /** Terminal tool: shared by [agentLoop] (stops on it) and [runModelTurn] (renders its message). */
        const val RESPOND_TOOL = "respond"

        /** [LlmConfig.extraParams] key for the local-backend GBNF; must match ai-core's `LocalLlmBackend.EXTRA_PARAM_GRAMMAR`. */
        private const val EXTRA_PARAM_GRAMMAR = "grammar"

        /**
         * GBNF forcing a weak local model to emit one well-formed `<tool_call>`;
         * passed to the local backend via [EXTRA_PARAM_GRAMMAR] (cloud ignores it).
         * `args` may be empty or hold multiple `"key":"value"` pairs; values are
         * JSON strings (newlines allowed). Keep the `tool` alternatives in sync with
         * the exposed handlers. `~` stands in for an escaped quote, substituted below.
         */
        private val localToolCallGrammar: String = """
            root ::= "<tool_call>{~tool~:" tool ",~args~:" args "}</tool_call>"
            tool ::= "~open_file~" | "~read_file~" | "~list_files~" | "~search_project~" | "~$RESPOND_TOOL~"
            args ::= "{}" | "{" pair ("," pair)* "}"
            pair ::= "~" key "~:~" val "~"
            key  ::= [a-z_]+
            val  ::= char*
            char ::= [^~\\] | "\\" [~\\/bfnrt]
        """.trimIndent().replace("~", "\\\"")
    }

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

    // Conversation history for LLM context (separate from UI messages)
    private val _history = MutableStateFlow<List<LlmInferenceService.ChatMessage>>(emptyList())
    val history: StateFlow<List<LlmInferenceService.ChatMessage>> = _history.asStateFlow()

    val currentSession: StateFlow<ChatSession?> = combine(_sessions, _currentSessionId) { sessions, id ->
        sessions.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private var currentBackendId: String = "local" // Default to local backend

    /**
     * Human-readable label for the backend the user has *selected* in settings, shown under the
     * chat input. This tracks the selection (the `ai_backend_preference`), NOT the
     * availability-resolved backend — selecting Gemini must read "Gemini API" even before its
     * API key check runs, otherwise it would always fall back to "Local LLM".
     */
    private val _activeBackendLabel = MutableStateFlow(selectedBackendLabel())
    val activeBackendLabel: StateFlow<String> = _activeBackendLabel.asStateFlow()

    private fun selectedBackendLabel(): String {
        val pref = getContext()?.getPluginSharedPreferences("AgentSettings")
            ?.getString("ai_backend_preference", "LOCAL_LLM")
        return when (pref) {
            "GEMINI" -> "Gemini API"
            else -> "Local LLM"
        }
    }

    /** Re-read the selected backend and update [activeBackendLabel]; call when returning to chat. */
    fun refreshBackendLabel() {
        _activeBackendLabel.value = selectedBackendLabel()
    }

    // Tool execution infrastructure
    private val approvalManager = ToolApprovalManager()
    private val toolRouter: ToolRouter
    private val executor: Executor
    private val agentLoop = AgentLoop(terminalTool = RESPOND_TOOL)
    val toolExecutionTracker = ToolExecutionTracker()

    /** The in-flight agent run (streaming + tool loop), so it can be cancelled. */
    private var generationJob: Job? = null
    private val generationEpoch = java.util.concurrent.atomic.AtomicInteger(0)

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
                // Read-only tools
                ReadFileHandler(context),
                ListFilesHandler(context),
                SearchProjectHandler(context),
                OpenFileHandler(context),
                ReadBuildOutputHandler(context),
                // Write tools
                CreateFileHandler(context),
                UpdateFileHandler(context),
                AddDependencyHandler(context),
                // Build tools
                com.itsaky.androidide.plugins.aiassistant.tool.handlers.RunAppHandler(context),
                GradleSyncHandler(context),
                // Template tool
                GenerateFromTemplateHandler(context)
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
                // Emit immutable snapshot to trigger StateFlow update
                _messages.value = session.messages.toList()
            }
        }
    }

    /**
     * Surface a configuration/setup problem to the user both ways: a persistent SYSTEM error
     * bubble in the transcript, and [AgentState.Error] so the fragment can show a transient,
     * actionable Snackbar. Used by the [sendMessage] pre-flight guards, which reject the request
     * before any backend runs — so the downstream `onError`/UserFeedback feedback never fires.
     */
    private fun emitSystemError(text: String) {
        val errorMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.SYSTEM,
            status = MessageStatus.ERROR
        )
        _messages.value = _messages.value + errorMessage
        syncMessageToSession(errorMessage)
        _agentState.value = AgentState.Error(text)
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
     * Build appropriate system prompt based on LLM backend.
     */
    private fun buildSystemPrompt(): String {
        return if (currentBackendId == "gemini") {
            buildSystemPromptGemini()
        } else {
            buildSystemPromptLocal()
        }
    }

    /**
     * System prompt for Gemini (high autonomy, structured tool calling via native functions).
     */
    private fun buildSystemPromptGemini(): String {
        val toolDescriptions = toolRouter.getAllHandlers().joinToString("\n") { handler ->
            "- ${handler.toolName}: ${handler.description}"
        }

        val prompt = """
        You are a senior Android developer integrated into AndroidIDE. Your goal is to build complete, working Android apps from user descriptions.

        AVAILABLE TOOLS:
        $toolDescriptions

        BEHAVIOR:
        - Create complete, production-ready code
        - Call tools proactively to build, test, and verify your work
        - Read files to understand project structure before making changes
        - After each file modification, verify the build compiles
        - Generate apps that actually run and work as described

        RULES:
        - Never fabricate tool output. Emit a tool call, then wait for the real result before continuing.
        - Never write "User:", "Assistant:", or "Tool results:" — the system supplies those.
        - Paths are relative to the project root and must be complete. If you don't know a file's exact path, find it with search_project or list_files first, then act on the real path — don't guess.
        - For plain chat (e.g. "Hi"), just reply briefly with no tool call. When the task is done, give a short summary with no tool call.

        TOOL CALL FORMAT — to run a tool, emit a single line in EXACTLY this format and nothing after it:
        <tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>
        Do NOT describe the action in prose (e.g. "Okay, I'll open the file…") — narrating does nothing.
        The tool only runs when you emit the <tool_call> line itself.

        FORMAT EXAMPLES (the tool call is the entire reply):
        Open a file once you know its path:
        <tool_call>{"tool":"open_file","args":{"file_path":"app/src/main/java/com/example/app/MainActivity.java"}}</tool_call>
        Find a file by name:
        <tool_call>{"tool":"search_project","args":{"query":"MainActivity"}}</tool_call>
        List a directory:
        <tool_call>{"tool":"list_files","args":{"directory":"app/src/main"}}</tool_call>

        WORKFLOW:
        1. Understand the user's request
        2. List files to understand the project structure
        3. Create/modify files with complete implementations
        4. Add dependencies if needed
        5. Sync gradle and verify compilation
        6. Run the app to confirm it works
        7. Report success and what was built
        """.trimIndent()

        android.util.Log.d("ChatViewModel", "Using Gemini system prompt (high autonomy mode) with ${toolRouter.getAllHandlers().size} tools")
        return prompt
    }

    /**
     * System prompt for local LLMs (guided step-by-step with text-based tool calling).
     */
    private fun buildSystemPromptLocal(): String {
        val toolDescriptions = toolRouter.getAllHandlers().joinToString("\n") { handler ->
            "- ${handler.toolName}: ${handler.description}"
        }

        val prompt = """
        You are a coding assistant inside AndroidIDE. You reply as the assistant ONLY.

        HARD RULES — follow exactly:
        - Produce ONE assistant reply, then STOP. Never write "User:", "Assistant:", or "Tool results:" — the system adds those and gives you real tool output.
        - Never invent or guess tool output. After you emit a tool call, STOP and wait; the real result is given back to you on the next turn.
        - Do an action by emitting a single tool call on its own line, in EXACTLY this format, and nothing after it:
          <tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>
        - To DO something (open/read/list/search a file), call that tool. Emit the
          tool call, then STOP and wait — the real result comes back next turn.
        - Every reply MUST be a single tool call. To talk to the user — a greeting, a
          question, or your final answer once the task is done — use the "respond"
          tool: <tool_call>{"tool":"respond","args":{"message":"..."}}</tool_call>.
        - "respond" ONLY talks; it does NOT perform actions. NEVER claim in a "respond"
          message that you opened, read, listed, created, or ran something — saying it
          does not make it happen. To actually do it, call the real tool FIRST, wait for
          its result, and only then "respond" about what the result showed.
        - Once the task is done, call "respond". Do NOT keep calling other tools.

        AVAILABLE TOOLS:
        $toolDescriptions
        - respond: Give the user a message or your final answer (use when done or just chatting)

        FINDING FILES:
        - You can pass just the file name (e.g. "MainActivity.java") — the system finds it in the project. A relative path also works. Don't invent deep paths.

        FORMAT EXAMPLES (the tool call is the entire reply):
        User asks to open a file -> call open_file (do NOT just say you opened it):
        <tool_call>{"tool":"open_file","args":{"file_path":"MainActivity.java"}}</tool_call>

        List a directory:
        <tool_call>{"tool":"list_files","args":{"directory":"app/src/main"}}</tool_call>

        Greeting or a plain question with no action to take:
        <tool_call>{"tool":"respond","args":{"message":"Hi! What would you like to build?"}}</tool_call>
        """.trimIndent()

        android.util.Log.d("ChatViewModel", "Using Local LLM system prompt (guided mode) with ${toolRouter.getAllHandlers().size} tools")
        return prompt
    }

    /**
     * Execute a batch of tool calls, render each result as a TOOL message, and
     * return the results so the [agentLoop] can feed them back to the model.
     * Does NOT touch [AgentState.Idle] — the loop owns the terminal state.
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCall>): List<ToolResult> {
        if (toolCalls.isEmpty()) return emptyList()

        val executingState = AgentState.Executing(
            currentStepIndex = 0,
            totalSteps = toolCalls.size,
            description = toolCalls.first().name
        )
        withContext(Dispatchers.Main) { _agentState.value = executingState }
        startStateTimer(executingState)

        val results = executor.execute(toolCalls)

        // Add tool results as messages
        withContext(Dispatchers.Main) {
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
                syncMessageToSession(resultMessage)
            }
        }

        stopStateTimer()
        return results
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
            emitSystemError("LLM service not available. Install the AI Core plugin.")
            return
        }

        if (!_isBackendAvailable.value) {
            android.util.Log.d("ChatViewModel", "sendMessage: Backend not available")
            emitSystemError(
                "No LLM backend is set up yet. Open Settings to select a local .gguf model, " +
                    "or add a Gemini API key."
            )
            return
        }

        if (userMessage.isBlank()) {
            android.util.Log.d("ChatViewModel", "sendMessage: Message is blank")
            return
        }

        // Guard against re-entry: an in-flight run must be stopped (Stop button)
        // before a new one starts, so the ViewModel is correct regardless of the
        // UI. Otherwise the old loop keeps executing tools and a second concurrent
        // generation races the first on the single-threaded backend.
        if (generationJob?.isActive == true) {
            android.util.Log.d("ChatViewModel", "sendMessage: generation already in progress; ignoring")
            return
        }

        android.util.Log.d("ChatViewModel", "sendMessage: Starting message processing")
        val epoch = generationEpoch.incrementAndGet()
        generationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Add user message to the UI.
                val userChatMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    text = userMessage,
                    sender = Sender.USER,
                    status = MessageStatus.SENT
                )
                withContext(Dispatchers.Main) {
                    _messages.value = _messages.value + userChatMessage
                    syncMessageToSession(userChatMessage)
                    _agentState.value = AgentState.Processing(str(R.string.msg_generating))
                }

                val config = LlmInferenceService.LlmConfig(currentBackendId).apply {
                    temperature = 0.7f
                    maxTokens = 4096  // headroom for complete tool calls
                    systemPrompt = buildSystemPrompt()
                    // Local backend constrains generation to this grammar; cloud
                    // backends ignore the key.
                    extraParams = mapOf(EXTRA_PARAM_GRAMMAR to localToolCallGrammar)
                }

                val messageWithContext = buildString {
                    append(userMessage)
                    append(buildContextString())
                }
                val history = _history.value.toMutableList()
                history.add(
                    LlmInferenceService.ChatMessage(
                        LlmInferenceService.ChatMessage.Role.USER,
                        messageWithContext
                    )
                )

                try {
                    agentLoop.run(
                        history = history,
                        generate = { prompt ->
                            withContext(Dispatchers.Main) {
                                _agentState.value = AgentState.Processing(str(R.string.msg_generating))
                            }
                            runModelTurn(llmService, prompt, config, epoch)
                        },
                        executeTools = { calls -> executeToolCalls(calls) },
                        events = object : AgentLoop.Events {
                            override suspend fun onMaxIterationsReached(turns: Int) {
                                addSystemMessage(
                                    str(R.string.agent_max_steps_reached, turns),
                                    MessageStatus.SENT
                                )
                            }

                            override suspend fun onRepeatedToolCalls(turns: Int) {
                                addSystemMessage(
                                    str(R.string.agent_repeated_calls),
                                    MessageStatus.SENT
                                )
                            }
                        }
                    )
                } finally {
                    _history.value = history.toList()
                    stopStateTimer()
                }

                withContext(Dispatchers.Main) { _agentState.value = AgentState.Idle }
            } catch (ce: CancellationException) {
                stopStateTimer()
                throw ce
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "sendMessage failed", e)
                stopStateTimer()
                _agentState.value = AgentState.Error(str(R.string.state_error, e.message))
                addSystemMessage(str(R.string.state_error, e.message), MessageStatus.ERROR)
            }
        }
    }

    /**
     * Run a single streaming model turn: create an agent bubble, stream tokens
     * into it, and suspend until completion. Returns the final response text (so
     * the [agentLoop] can extract tool calls from it). Throws on backend error.
     */
    private suspend fun runModelTurn(
        llmService: LlmInferenceService,
        prompt: String,
        config: LlmInferenceService.LlmConfig,
        epoch: Int
    ): String {
        val deferred = CompletableDeferred<String>()
        val agentMessageId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        val responseBuilder = StringBuilder()

        // True once Stop (or a newer message) has superseded this generation.
        fun isStale() = generationEpoch.get() != epoch

        withContext(Dispatchers.Main) {
            val agentMessage = ChatMessage(
                id = agentMessageId,
                text = "",
                sender = Sender.AGENT,
                status = MessageStatus.SENT
            )
            _messages.value = _messages.value + agentMessage
            syncMessageToSession(agentMessage)
        }

        try {
            llmService.generateStreaming(prompt, config, object : LlmInferenceService.StreamCallback {
                override fun onToken(token: String) {
                    if (isStale()) return  // Stop pressed — ignore late tokens.
                    responseBuilder.append(token)
                    viewModelScope.launch(Dispatchers.Main) {
                        if (isStale()) return@launch
                        val updated = ChatMessage(
                            id = agentMessageId,
                            text = responseBuilder.toString(),
                            sender = Sender.AGENT,
                            status = MessageStatus.SENT
                        )
                        _messages.value = _messages.value.map { if (it.id == agentMessageId) updated else it }
                        syncMessageToSession(updated)
                    }
                }

                override fun onComplete(response: LlmInferenceService.LlmResponse) {
                    if (isStale()) {
                        // Already cancelled; the awaiting loop was unblocked by job cancel.
                        deferred.complete(response.text)
                        return
                    }
                    val durationMs = System.currentTimeMillis() - startTime
                    val toolCalls = ToolCallExtractor.extractToolCalls(response.text)
                    val respondCall = toolCalls.firstOrNull { it.name == RESPOND_TOOL }
                    val respondMessage = respondCall?.args?.get("message")?.toString()

                    val lastToolResult = _messages.value.lastOrNull { it.sender == Sender.TOOL }
                    val lastToolFailed = lastToolResult?.status == MessageStatus.ERROR

                    val displayText = when {
                        respondCall != null && lastToolFailed ->
                            str(R.string.agent_action_failed)
                        // A "respond" call is the model's message to the user; render
                        // it (falling back if the model left the message blank) rather
                        // than showing a "🔧 respond()" tool badge.
                        respondCall != null ->
                            respondMessage?.takeIf { it.isNotBlank() } ?: str(R.string.agent_no_response)
                        toolCalls.isNotEmpty() -> toolCalls.joinToString("\n") { c ->
                            "🔧 ${c.name}(${c.args.entries.joinToString(", ") { "${it.key}=${it.value}" }})"
                        }
                        else -> response.text.ifBlank {
                            str(R.string.agent_no_response)
                        }
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        if (isStale()) return@launch
                        val finalMsg = ChatMessage(
                            id = agentMessageId,
                            text = displayText,
                            sender = Sender.AGENT,
                            status = MessageStatus.COMPLETED,
                            durationMs = durationMs
                        )
                        _messages.value = _messages.value.map { if (it.id == agentMessageId) finalMsg else it }
                        syncMessageToSession(finalMsg)
                    }
                    // Return the RAW text to the loop so extraction/stop logic is unaffected.
                    deferred.complete(response.text)
                }

                override fun onError(error: String) {
                    if (isStale()) {
                        deferred.completeExceptionally(CancellationException("stopped"))
                        return
                    }
                    viewModelScope.launch(Dispatchers.Main) {
                        // Drop the empty/partial bubble; the error surfaces as a SYSTEM message.
                        _messages.value = _messages.value.filter { it.id != agentMessageId }
                    }
                    deferred.completeExceptionally(RuntimeException(error))
                }
            })
        } catch (e: Exception) {
            // A synchronous throw fires no callback, so complete `deferred` here or
            // await() below hangs forever.
            android.util.Log.e("ChatViewModel", "generateStreaming threw synchronously", e)
            viewModelScope.launch(Dispatchers.Main) {
                _messages.value = _messages.value.filter { it.id != agentMessageId }
            }
            if (!deferred.isCompleted) deferred.completeExceptionally(e)
        }

        return deferred.await()
    }

    /** Append an AGENT message to the chat (terminal state, no streaming dots). */
    private suspend fun addAgentMessage(text: String) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.AGENT,
            status = MessageStatus.COMPLETED,
            durationMs = 0L
        )
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + message
            syncMessageToSession(message)
        }
    }

    /** Resolve a UI string resource via the plugin's Android context. */
    private fun str(resId: Int, vararg args: Any?): String =
        getContext()?.androidContext?.getString(resId, *args).orEmpty()

    /** Append a SYSTEM message to the chat (on the main thread). */
    private suspend fun addSystemMessage(text: String, status: MessageStatus) {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.SYSTEM,
            status = status
        )
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + message
            syncMessageToSession(message)
        }
    }

    /**
     * Clear all messages from the conversation.
     */
    fun clearMessages() {
        // Clear Chat must also stop any in-flight run, not just wipe the list.
        generationEpoch.incrementAndGet()
        generationJob?.cancel()
        generationJob = null
        getLlmService()?.cancelGeneration()
        stopStateTimer()
        _messages.value = emptyList()
        _history.value = emptyList()
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
            // Use immutable snapshot to ensure StateFlow emits on mutations
            _messages.value = session.messages.toList()
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
        generationEpoch.incrementAndGet()
        _agentState.value = AgentState.Cancelling
        generationJob?.cancel()
        generationJob = null
        getLlmService()?.cancelGeneration()
        stopStateTimer()
        finalizeInProgressMessages()
        _agentState.value = AgentState.Idle
    }

    /**
     * Give any still-streaming agent bubble (status SENT, null `durationMs`) a
     * terminal state so its animated "…" dots stop: drop empty bubbles, mark
     * partial ones [MessageStatus.COMPLETED]. Called on Stop.
     */
    private fun finalizeInProgressMessages() {
        val finalized = _messages.value.mapNotNull { msg ->
            if (msg.sender == Sender.AGENT && msg.durationMs == null) {
                if (msg.text.isBlank()) null
                else msg.copy(status = MessageStatus.COMPLETED, durationMs = 0L)
            } else {
                msg
            }
        }
        _messages.value = finalized

        // Mirror the change into the current session's backing list.
        _currentSessionId.value?.let { sessionId ->
            _sessions.value.firstOrNull { it.id == sessionId }?.let { session ->
                session.messages.clear()
                session.messages.addAll(finalized)
            }
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
                delay(100)
                val current = _agentState.value
                if (current !is AgentState.Executing) break
                _agentState.value = current.copy(
                    elapsedMillis = System.currentTimeMillis() - current.startTime
                )
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
