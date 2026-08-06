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
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.EditFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.PathGuard
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.GenerateFromTemplateHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.GradleSyncHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ListFilesHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.OpenFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadBuildOutputHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.ReadFileHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.SearchProjectHandler
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.UpdateFileHandler
import com.itsaky.androidide.plugins.aiassistant.data.ChatStorageManager
import com.itsaky.androidide.plugins.aiassistant.utils.AgentTrace
import com.itsaky.androidide.plugins.services.IdeEditorService
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

        /**
         * [LlmConfig.extraParams] key for the local-backend GBNF; must match ai-core's
         * `LocalLlmBackend.EXTRA_PARAM_GRAMMAR`.
         */
        private const val EXTRA_PARAM_GRAMMAR = "grammar"

        /** Per-argument cap in the tool badge shown in the transcript. */
        private const val TOOL_BADGE_ARG_LIMIT = 80

        /** Near-greedy sampling for local models, whose tool arguments must be copied, not invented. */
        private const val LOCAL_TEMPERATURE = 0.15f

        /** Max open files named in the prompt's IDE-context block. */
        private const val MAX_CONTEXT_OPEN_FILES = 8

        /**
         * Path used in the tool-call examples when the IDE has nothing open, so there is no real one
         * to show. A concrete path is what a small model needs to copy the *shape* from — a
         * placeholder like "path/to/File.ext" measurably degrades its calls — so this is the
         * dominant CoGo project layout rather than a language-neutral token. Whenever a file *is*
         * open, [IdeSnapshot.exampleFilePath] uses that instead and this is never seen.
         */
        private const val FALLBACK_EXAMPLE_PATH = "app/src/main/java/com/example/MainActivity.kt"
    }

    /**
     * Builds the local-backend GBNF forcing one well-formed `<tool_call>`. The `tool`
     * alternatives come from the registered handlers (+ [RESPOND_TOOL]) so the token
     * mask can't drift; control chars are excluded so `org.json` accepts the strings.
     * @return the GBNF grammar string.
     */
    private fun buildLocalToolCallGrammar(): String {
        val toolNames = (toolRouter.getAllHandlers().map { it.toolName } + RESPOND_TOOL).distinct()
        val toolAlternatives = toolNames.joinToString(" | ") { "\"~$it~\"" }
        return """
            root ::= "<tool_call>{~tool~:" tool ",~args~:" args "}</tool_call>"
            tool ::= $toolAlternatives
            args ::= "{}" | "{" pair ("," pair)* "}"
            pair ::= "~" key "~:~" val "~"
            key  ::= [a-z_]+
            val  ::= char*
            char ::= [^~\\\x00-\x1F] | "\\" [~\\/bfnrt]
        """.trimIndent().replace("~", "\\\"")
    }

    /** Local-backend GBNF, built once from the registered handlers. */
    private val localToolCallGrammar: String by lazy { buildLocalToolCallGrammar() }

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
     * Label for the backend the user *selected* in settings, shown under the chat input. Tracks the
     * selection, not the availability-resolved backend: picking Gemini must read "Gemini API" before
     * its key check runs, or it would always show "Local LLM".
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

    /** True while a generation is admitted and its coroutine has not yet unwound; gates re-entry. */
    private val isGenerating = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether the current run's most recent tool batch failed; reset per run. */
    @Volatile
    private var lastToolFailedThisRun = false

    /** The current run's most recent fully-successful tool batch, or null; reset per run. */
    @Volatile
    private var lastSucceededCalls: List<ToolCall>? = null

    /** The tool awaiting approval, straight from [approvalManager] — no polling in between. */
    val pendingApprovalRequest: StateFlow<ApprovalRequest?> = approvalManager.currentApprovalRequest

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
                EditFileHandler(context),
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
    fun submitApproval(result: ApprovalResult, correction: String? = null) {
        approvalManager.submitApproval(result, correction)
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
     * Surfaces a setup problem both ways: a persistent SYSTEM bubble and [AgentState.Error] for the
     * fragment's Snackbar. Used by [sendMessage]'s pre-flight guards, which reject before any backend
     * runs, so the downstream `onError`/UserFeedback path never fires.
     * @param text the error text to show.
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
    private suspend fun buildSystemPrompt(): String {
        // One editor read serves both the IDE CONTEXT block and the paths in the examples.
        val ide = readIdeSnapshot()
        val examplePath = ide.exampleFilePath()
        val base = if (currentBackendId == "gemini") {
            buildSystemPromptGemini(examplePath)
        } else {
            buildSystemPromptLocal(examplePath)
        }
        return base + ide.contextBlock()
    }

    /**
     * What the IDE has open, project-relative, read once per prompt.
     * @property currentFile the focused file, or null when nothing is open.
     * @property otherFiles other open tabs, capped at [MAX_CONTEXT_OPEN_FILES].
     */
    private data class IdeSnapshot(val currentFile: String?, val otherFiles: List<String>)

    /**
     * Reads the open-file state from the editor service.
     * @return the snapshot; empty when there is no editor service or the call fails.
     */
    private suspend fun readIdeSnapshot(): IdeSnapshot {
        val editor = getContext()?.services?.get(IdeEditorService::class.java)
            ?: return IdeSnapshot(null, emptyList())
        val root = File(PathGuard.projectRoot())

        // Editor state is read on the main thread, like every other editor-service call here.
        val (current, open) = withContext(Dispatchers.Main) {
            runCatching { editor.getCurrentFile() to editor.getOpenFiles() }
                .getOrDefault(null to emptyList())
        }

        fun relative(file: File): String = runCatching { file.relativeToOrSelf(root).path }
            .getOrDefault(file.name)

        return IdeSnapshot(
            currentFile = current?.let(::relative),
            otherFiles = open.orEmpty()
                .filter { it != current }
                .take(MAX_CONTEXT_OPEN_FILES)
                .map(::relative),
        )
    }

    /**
     * The path the tool-call examples should use: a file the IDE really has open, so the examples
     * carry this project's own language and layout instead of teaching an Android/Java one. Falls
     * back to [FALLBACK_EXAMPLE_PATH] only when nothing is open.
     * @return a project-relative path.
     */
    private fun IdeSnapshot.exampleFilePath(): String =
        currentFile ?: otherFiles.firstOrNull() ?: FALLBACK_EXAMPLE_PATH

    /**
     * Describes what the user is looking at: the focused file and other open tabs, project-relative.
     * Most requests are about the file on screen and the IDE knows that path exactly; without it the
     * model reconstructs one, which is where invented `.java` paths for Kotlin files came from.
     * @return a prompt block, or empty when nothing is open.
     */
    private fun IdeSnapshot.contextBlock(): String {
        if (currentFile == null && otherFiles.isEmpty()) return ""

        return buildString {
            append("\n\nIDE CONTEXT (real paths — use these verbatim, do not rewrite them):\n")
            currentFile?.let { append("- File the user is viewing: ").append(it).append("\n") }
            if (otherFiles.isNotEmpty()) {
                append("- Other open files: ").append(otherFiles.joinToString(", ")).append("\n")
            }
            append(
                "If the user names a file that appears above, use that exact path and do not " +
                    "guess a different folder or extension."
            )
        }
    }

    /**
     * System prompt for Gemini (high autonomy, structured tool calling via native functions).
     * @param examplePath path shown in the tool-call examples — this project's own open file when
     *   there is one, so the examples never imply a language or layout the project doesn't have.
     */
    private fun buildSystemPromptGemini(examplePath: String): String {
        val toolDescriptions = toolRouter.getAllHandlers().joinToString("\n") { handler ->
            "- ${handler.toolName}: ${handler.description}"
        }

        val prompt = """
        You are a senior Android developer integrated into CodeOnTheGo. Your goal is to build complete, working Android apps from user descriptions.

        AVAILABLE TOOLS:
        $toolDescriptions
        - respond: Send the user your reply or final answer. It MUST carry a "message" holding the
          text itself — a respond call with no "message" shows the user nothing.

        BEHAVIOR:
        - Create complete, production-ready code
        - Call tools proactively to build, test, and verify your work
        - Read files to understand project structure before making changes
        - After each file modification, verify the build compiles
        - Generate apps that actually run and work as described

        RULES:
        - Emit ONE tool call per reply, then stop and wait. Do NOT plan a batch: a tool whose arguments depend on another tool's result (editing a file you just searched for) cannot use a result you have not received yet.
        - To locate a file, call search_project ONCE with its name — it searches the whole project. Never walk the tree with repeated list_files calls; you have a limited number of turns and each level wastes one.
        - Renaming a symbol everywhere in a file is ONE edit_file with replace_all set to true and old_string set to just the symbol — not one edit per line.
        - To change an existing file, use edit_file (find/replace an exact snippet), not update_file — a whole-file rewrite gets truncated before it reaches disk.
        - Before edit_file, read the exact file you are about to edit with read_file, and copy old_string byte-for-byte from that output, including indentation. Never edit a path you have not confirmed exists.
        - old_string must be the text currently in the file and new_string what it should become. If they are identical the edit is rejected.
        - Never fabricate tool output. Emit a tool call, then wait for the real result before continuing.
        - Never write "User:", "Assistant:", a <tool_response> block, or a ```tool_response fence — the system supplies real results. Any tool output you write yourself is a hallucination and will be ignored.
        - Paths are relative to the project root and must be complete. If you don't know a file's exact path, find it with search_project or list_files first, then act on the real path — don't guess.
        - For plain chat (e.g. "Hi"), just reply briefly with no tool call. When the task is done, either give a short summary with no tool call, or end with a single respond call carrying that summary in its "message" — never an empty respond.

        TOOL CALL FORMAT — to run a tool, emit a single line in EXACTLY this format and nothing after it:
        <tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>
        Do NOT describe the action in prose (e.g. "Okay, I'll open the file…") — narrating does nothing.
        The tool only runs when you emit the <tool_call> line itself.

        FORMAT EXAMPLES (the tool call is the entire reply; the paths are this project's — reuse a path
        only when it is the file you actually mean):
        Report the finished task (the summary goes in "message"):
        <tool_call>{"tool":"respond","args":{"message":"Renamed count to itemCount."}}</tool_call>
        Open a file once you know its path:
        <tool_call>{"tool":"open_file","args":{"file_path":"$examplePath"}}</tool_call>
        Find a file by name:
        <tool_call>{"tool":"search_project","args":{"query":"${exampleFileStem(examplePath)}"}}</tool_call>
        List the project's top-level files (an empty directory means the project root):
        <tool_call>{"tool":"list_files","args":{"directory":""}}</tool_call>
        Change part of a file (line breaks inside a value MUST be written as \n):
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"count = 0","new_string":"count = 1"}}</tool_call>

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
     * File name without its extension, for a `search_project` example that matches [examplePath].
     * @param examplePath the example path.
     * @return the bare stem (e.g. "MainActivity").
     */
    private fun exampleFileStem(examplePath: String): String =
        examplePath.substringAfterLast('/').substringBeforeLast('.')

    /**
     * System prompt for local LLMs (guided step-by-step with text-based tool calling).
     * @param examplePath path shown in the tool-call examples — this project's own open file when
     *   there is one, so the examples never imply a language or layout the project doesn't have.
     */
    private fun buildSystemPromptLocal(examplePath: String): String {
        val toolDescriptions = toolRouter.getAllHandlers().joinToString("\n") { handler ->
            "- ${handler.toolName}: ${handler.description}"
        }

        val prompt = """
        You are a coding assistant inside CodeOnTheGo.

        Rules:
        - Reply with exactly ONE tool call, nothing else.
        - Use a file/project tool only when the user asks about files, code, or the project; for a greeting, small talk, or a question you can answer, use "respond".
        - Never invent tool output or claim an action you didn't perform via a tool. After a tool call, stop; the real result returns next turn.
        - "respond" must carry a "message" — your reply or final answer.
        - read_file and open_file accept a bare file name (the project is searched for it). Never invent deep paths.
        - To change a file, use edit_file, not update_file. Call read_file FIRST, then copy the text to replace into "old_string" EXACTLY as it appears in that output (same spelling, same indentation). It must appear only once — include the line above or below if it doesn't.
        - "old_string" is the text that is in the file NOW; "new_string" is what it should become. They must differ. To rename x to y: old_string has x, new_string has y.
        - Never put a real line break inside an argument value: write it as \n. Keep old_string/new_string to a few lines; make several small edits rather than one big one.
        - edit_file needs a real path, not a bare name, and never a path you invented. If you don't know it, call search_project with the file name FIRST and use the path it returns — don't guess the folders, and don't guess the extension (.kt vs .java).
        - To rename something everywhere in a file, make ONE edit_file call with old_string set to just the old name and "replace_all":"true".

        Tools:
        $toolDescriptions
        - respond: Send the user a message or your final answer.

        Examples (pick the tool that matches; copy the FORMAT, not the values):
        Greeting / question you can answer -> respond:
        <tool_call>{"tool":"respond","args":{"message":"Hi! What would you like to build?"}}</tool_call>
        Open a file (a bare name is fine here) -> open_file:
        <tool_call>{"tool":"open_file","args":{"file_path":"${examplePath.substringAfterLast('/')}"}}</tool_call>
        Change one line of a file -> edit_file:
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"setTitle(\"Old\")","new_string":"setTitle(\"New\")"}}</tool_call>
        Change two lines (note the \n, never a real line break) -> edit_file:
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"a = 1\nb = 2","new_string":"a = 10\nb = 20"}}</tool_call>
        Rename every use of one name in a file -> ONE edit_file with replace_all (NOT one call per line):
        <tool_call>{"tool":"edit_file","args":{"file_path":"$examplePath","old_string":"oldName","new_string":"newName","replace_all":"true"}}</tool_call>
        Find where a file actually lives before editing it -> search_project:
        <tool_call>{"tool":"search_project","args":{"query":"${exampleFileStem(examplePath)}"}}</tool_call>
        """.trimIndent()

        android.util.Log.d("ChatViewModel", "Using Local LLM system prompt (guided mode) with ${toolRouter.getAllHandlers().size} tools")
        return prompt
    }

    /**
     * Executes a batch of tool calls, renders each result as a TOOL message, and
     * returns the results for the [agentLoop] to feed back; leaves [AgentState.Idle]
     * to the loop.
     * @param toolCalls the calls to execute.
     * @return the results, positionally aligned with [toolCalls].
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

        // Record whether this batch's last tool failed (read by runModelTurn).
        lastToolFailedThisRun = results.lastOrNull()?.success == false

        lastSucceededCalls = toolCalls.takeIf { results.isNotEmpty() && results.all { r -> r.success } }

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
        android.util.Log.d("ChatViewModel", "sendMessage called")
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

        // Reject re-entry while a generation is still in flight.
        if (!isGenerating.compareAndSet(false, true)) {
            android.util.Log.d("ChatViewModel", "sendMessage: generation already in progress; ignoring")
            return
        }

        AgentTrace.beginRun(currentBackendId, userMessage, contextFiles.size)
        // Reset per-run tool tracking.
        lastToolFailedThisRun = false
        lastSucceededCalls = null
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
                    // The grammar shapes a local tool call but not its values, so paths get sampled.
                    temperature = if (currentBackendId == "gemini") 0.7f else LOCAL_TEMPERATURE
                    maxTokens = 4096  // headroom for complete tool calls
                    systemPrompt = buildSystemPrompt()
                    // Local backend constrains generation to this grammar; cloud ignores it.
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
                    val loopResult = agentLoop.run(
                        history = history,
                        generate = { turns ->
                            withContext(Dispatchers.Main) {
                                _agentState.value = AgentState.Processing(str(R.string.msg_generating))
                            }
                            runModelTurn(llmService, turns, config, epoch)
                        },
                        executeTools = { calls -> executeToolCalls(calls) },
                        events = object : AgentLoop.Events {
                            override suspend fun onToolResults(
                                turn: Int,
                                calls: List<ToolCall>,
                                results: List<ToolResult>,
                            ) {
                                calls.forEachIndexed { index, call ->
                                    val result = results.getOrNull(index)
                                    AgentTrace.stage(
                                        "RESULT",
                                        "turn=$turn ${call.name} success=${result?.success}",
                                        AgentTrace.preview(result?.message),
                                    )
                                }
                            }

                            override suspend fun onFinalAnswer(turn: Int, message: String) {
                                AgentTrace.stage(
                                    "ANSWER",
                                    "turn=$turn chars=${message.length}",
                                    AgentTrace.preview(message),
                                )
                            }

                            override suspend fun onMaxIterationsReached(turns: Int) {
                                AgentTrace.refusal("LOOP", "turns=$turns", "iteration cap reached")
                                addSystemMessage(
                                    str(R.string.agent_max_steps_reached, turns),
                                    MessageStatus.SENT
                                )
                            }

                            override suspend fun onRepeatedToolCalls(turns: Int) {
                                AgentTrace.refusal("LOOP", "turns=$turns", "identical tool calls repeated")
                                addSystemMessage(
                                    str(R.string.agent_repeated_calls),
                                    MessageStatus.SENT
                                )
                            }
                        }
                    )
                    AgentTrace.endRun(loopResult.reason.name, loopResult.turns)
                } finally {
                    // Persist history only if this run wasn't superseded (epoch bumped).
                    if (generationEpoch.get() == epoch) {
                        _history.value = history.toList()
                    }
                    stopStateTimer()
                }

                withContext(Dispatchers.Main) { _agentState.value = AgentState.Idle }
            } catch (ce: CancellationException) {
                AgentTrace.endRun("cancelled")
                stopStateTimer()
                throw ce
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "sendMessage failed", e)
                AgentTrace.endRun("error: ${e.message}")
                stopStateTimer()
                _agentState.value = AgentState.Error(str(R.string.state_error, e.message))
                addSystemMessage(str(R.string.state_error, e.message), MessageStatus.ERROR)
            } finally {
                // Allow re-entry once the coroutine unwinds.
                isGenerating.set(false)
            }
        }
    }

    /**
     * Runs one streaming model turn: creates an agent bubble, streams tokens into it and suspends
     * until completion, throwing on backend error. Sends [turns] structurally to the local backend;
     * Gemini's transport carries one string, so it keeps the flattened transcript.
     * @param llmService the inference service.
     * @param turns the conversation so far; the last entry is the current user turn.
     * @param config the generation config.
     * @param epoch this run's epoch, for staleness checks against Stop/newer sends.
     * @return the final response text (raw, for tool-call extraction).
     */
    private suspend fun runModelTurn(
        llmService: LlmInferenceService,
        turns: List<LlmInferenceService.ChatMessage>,
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

        val streamCallback = object : LlmInferenceService.StreamCallback {
                override fun onToken(token: String) {
                    if (isStale()) return  // Stop pressed — ignore late tokens.
                    responseBuilder.append(token)
                    // Snapshot on the producer thread; only the immutable String crosses to Main.
                    val snapshot = responseBuilder.toString()
                    viewModelScope.launch(Dispatchers.Main) {
                        if (isStale()) return@launch
                        val updated = ChatMessage(
                            id = agentMessageId,
                            text = snapshot,
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
                    // Not from onModelTurn, which fires later and would order the trace wrongly.
                    AgentTrace.stage(
                        "LLM",
                        "chars=${response.text.length} generateMs=$durationMs",
                        AgentTrace.preview(response.text),
                    )
                    val toolCalls = ToolCallExtractor.extractToolCalls(response.text)
                    // Where a mis-escaped generation quietly becomes "the model said nothing".
                    if (toolCalls.isEmpty()) {
                        AgentTrace.detail("PARSE", "calls=0 generateMs=$durationMs (plain reply or unparsable)")
                    } else {
                        AgentTrace.stage(
                            "PARSE",
                            "calls=${toolCalls.size} generateMs=$durationMs",
                            toolCalls.joinToString("; ") { "${it.name}(${AgentTrace.previewArgs(it.args)})" },
                        )
                    }
                    // Per-run flag (set by executeToolCalls), not a session-wide scan.
                    val lastToolFailed = lastToolFailedThisRun

                    val realCalls = toolCalls.filterNot { it.name == RESPOND_TOOL }
                    if (realCalls.isNotEmpty() && realCalls == lastSucceededCalls) {
                        viewModelScope.launch(Dispatchers.Main) {
                            _messages.value = _messages.value.filter { it.id != agentMessageId }
                        }
                        deferred.complete(response.text)
                        return
                    }

                    val displayText = AgentReplyRenderer.render(
                        rawText = response.text,
                        toolCalls = toolCalls,
                        terminalTool = RESPOND_TOOL,
                        lastToolFailed = lastToolFailed,
                        actionFailedText = str(R.string.agent_action_failed),
                        noResponseText = str(R.string.agent_no_response),
                    ) { c ->
                        // Capped: edit_file snippets would turn the badge into a wall of source.
                        "🔧 ${c.name}(${c.args.entries.joinToString(", ") { "${it.key}=${abbreviate(it.value)}" }})"
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
            }

        try {
            if (currentBackendId == "gemini") {
                llmService.generateStreaming(agentLoop.renderTranscript(turns), config, streamCallback)
            } else {
                llmService.generateStreamingWithTools(
                    turns.lastOrNull()?.content.orEmpty(),
                    turns.dropLast(1),
                    config,
                    emptyList(),
                    object : LlmInferenceService.ToolStreamCallback {
                        override fun onToken(token: String) = streamCallback.onToken(token)
                        override fun onToolCall(request: LlmInferenceService.ToolCallRequest) = Unit
                        override fun onComplete(response: LlmInferenceService.LlmResponse) =
                            streamCallback.onComplete(response)
                        override fun onError(error: String) = streamCallback.onError(error)
                    }
                )
            }
        } catch (e: Exception) {
            // A synchronous throw fires no callback; complete deferred so await() doesn't hang.
            android.util.Log.e("ChatViewModel", "generateStreaming threw synchronously", e)
            viewModelScope.launch(Dispatchers.Main) {
                _messages.value = _messages.value.filter { it.id != agentMessageId }
            }
            if (!deferred.isCompleted) deferred.completeExceptionally(e)
        }

        return deferred.await()
    }

    /**
     * Appends an AGENT message to the chat (terminal state, no streaming dots).
     * @param text the message text.
     */
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

    /**
     * Resolves a UI string resource via the plugin's Android context.
     * @param resId the string resource id.
     * @param args format arguments.
     * @return the resolved string, or empty if the context is gone.
     */
    private fun str(resId: Int, vararg args: Any?): String =
        getContext()?.androidContext?.getString(resId, *args).orEmpty()

    /**
     * Renders a tool argument for the one-line tool badge: line breaks flattened and the
     * value capped, so a code-carrying argument stays a badge instead of a source dump.
     * @param value the raw argument value.
     * @return a single-line, length-capped rendering.
     */
    private fun abbreviate(value: Any?): String {
        val text = value?.toString().orEmpty().replace("\n", "⏎")
        return if (text.length <= TOOL_BADGE_ARG_LIMIT) text
        else text.take(TOOL_BADGE_ARG_LIMIT) + "…"
    }

    /**
     * Appends a SYSTEM message to the chat (on the main thread).
     * @param text the message text.
     * @param status the message status.
     */
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
        approvalManager.cancelPendingApproval()
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
        _history.value = emptyList()
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
            _history.value = emptyList()
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
        // Cancelling the job alone would strand an open approval dialog with nothing awaiting it.
        approvalManager.cancelPendingApproval()
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
