package com.example.llama

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import java.util.concurrent.atomic.AtomicLong

private const val SYSTEM_PROMPT = """
 You are a helpful and smart assistant integrated into an Android application.
 You have access to the following tools:
 [AVAILABLE_TOOLS]

 When a user asks a question, you must follow these steps:
 1.  Determine if any of the available tools can help answer the question.
 2.  If a tool is appropriate, you MUST respond with a single `<tool_call>` XML tag. The tag should contain a JSON object with the tool's name and its arguments.
 3.  If no tool is needed, you should answer the user's question directly.
 """

/**
 * The single source of truth for chat data and business logic.
 * This class manages the conversation state and all interactions with the LlmInferenceEngine.
 */
class LocalLlmRepositoryImpl(
    private val application: Application,
    private val engine: LlmInferenceEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val tag: String = this::class.java.simpleName
    private val messageIdCounter = AtomicLong(0)

    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool(),
        GetWeatherTool()
    ).associateBy { it.name }

    private var currentModelFamily: ModelFamily = ModelFamily.UNKNOWN

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
//            UiMessage(
//                id = messageIdCounter.getAndIncrement(),
//                text = "Hello! How can I help you today?",
//                type = MessageType.MODEL
//            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var loadedModelPath: String? = null

    // --- Public API for ViewModel ---

    suspend fun sendMessage(
        userInput: String,
        isStreaming: Boolean,
        isToolUseEnabled: Boolean
    ) {
        addMessage(userInput, Sender.USER)
        val placeholder = if (isStreaming) "" else "..."
        addMessage(placeholder, Sender.AGENT)

        if (isToolUseEnabled) {
            runAgentLoop()
        } else {
            val fullPrompt = buildPromptWithHistory(_messages.value, false)
            runSimpleInference(fullPrompt, isStreaming)
        }
    }

    suspend fun loadModel(pathToModel: String) {
        if (pathToModel == loadedModelPath) {
            val message = "Model is already loaded."
            Log.d(tag, message)
            addMessage(message, Sender.SYSTEM)
            return
        }

        try {
            if (loadedModelPath != null) {
                Log.d(tag, "Switching models. Unloading: $loadedModelPath")
                addMessage("Unloading previous model...", Sender.SYSTEM)
                withContext(ioDispatcher) {
                    engine.unloadModel()
                }
            }

            currentModelFamily = detectModelFamily(pathToModel)
            addMessage("Detected model family: $currentModelFamily", Sender.SYSTEM)
            withContext(ioDispatcher) {
                engine.loadModel(pathToModel)
            }

            loadedModelPath = pathToModel
            addMessage("Loaded $pathToModel", Sender.SYSTEM)

            val contextSize = engine.getContextSize()
            addMessage("Model context size: $contextSize tokens", Sender.SYSTEM)

        } catch (exc: IllegalStateException) {
            Log.e(tag, "loadModel() failed", exc)
            addMessage(
                exc.message ?: "An unknown error occurred during model loading.",
                Sender.SYSTEM
            )
            loadedModelPath = null
        }
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        try {
            val start = System.nanoTime()
            val warmupResult = engine.bench(pp, tg, pl, nr)
            val end = System.nanoTime()
            addMessage(warmupResult, Sender.AGENT)

            val warmupTime = (end - start).toDouble() / 1_000_000_000.0
            addMessage("Warm up time: $warmupTime seconds, please wait...", Sender.SYSTEM)

            if (warmupTime > 5.0) {
                addMessage("Warm up took too long, aborting benchmark", Sender.SYSTEM)
                return
            }
            val benchmarkResult = engine.bench(512, 128, 1, 3)
            addMessage(benchmarkResult, Sender.SYSTEM)
        } catch (exc: IllegalStateException) {
            Log.e(tag, "bench() failed", exc)
            addMessage(
                exc.message ?: "An unknown error occurred during benchmark.",
                Sender.SYSTEM
            )
        }
    }

    fun clear() {
        _messages.value = listOf(
//            UiMessage(
//                id = messageIdCounter.getAndIncrement(),
//                text = "Hello! How can I help you today?",
//                type = MessageType.MODEL
//            )
        )
    }

    private suspend fun runSimpleInference(prompt: String, isStreaming: Boolean) {
        val startTime = System.nanoTime()
        try {
            withContext(ioDispatcher) {
                if (isStreaming) {
                    var currentText = ""
                    engine.runStreamingInference(prompt).collect { responseChunk ->
                        currentText += responseChunk
                        updateLastMessage(currentText)
                    }
                } else {
                    val modelResponse = engine.runInference(prompt)
                    updateLastMessage(modelResponse)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Inference failed", e)
            updateLastMessage("Error: ${e.message}")
        } finally {
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            updateLastMessageDuration(durationMs)
        }
    }

    @OptIn(InternalSerializationApi::class)
    private suspend fun runAgentLoop(maxTurns: Int = 5) {
        var currentTurn = 0
        while (currentTurn < maxTurns) {
            Log.d("AgentDebug", "--- [Step ${currentTurn + 1}] ---")
            val currentHistory = _messages.value
            val isFinalAnswerTurn =
                currentHistory.getOrNull(currentHistory.size - 2)?.type == Sender.TOOL

            // 2. UPDATE THE STOP STRINGS
            val stopStrings = if (isFinalAnswerTurn) {
                // For the final answer, stop before the model hallucinates a new question.
                listOf("Question:", "\n\n")
            } else {
                // For tool selection, we now expect a structured XML tag.
                listOf("</tool_call>")
            }

            val fullPromptHistory = buildPromptWithHistory(currentHistory, isFinalAnswerTurn)
            Log.d("AgentDebug", "Final Prompt Sent:\n$fullPromptHistory")
            val startTime = System.nanoTime()
            val modelResponse = try {
                withContext(Dispatchers.IO) {
                    engine.runInference(fullPromptHistory, stopStrings = stopStrings)
                }
            } catch (e: Exception) {
                Log.e("AgentLoop", "Model inference failed", e)
                "Error: Could not get a response from the model."
            }
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            val finalResponse = modelResponse.split(stopStrings.first()).first()
            Log.d("AgentDebug", "Raw Model Result: \"$modelResponse\"")
            Log.d("AgentDebug", "Trimmed Final Result: \"$finalResponse\"")

            if (isFinalAnswerTurn) {
                var cleanResponse = finalResponse
                for (stopWord in stopStrings) {
                    if (cleanResponse.contains(stopWord)) {
                        // Take only the text *before* the first occurrence of a stop word
                        cleanResponse = cleanResponse.substringBefore(stopWord).trim()
                    }
                }
                updateLastMessage(cleanResponse)

                Log.d("AgentDebug", "Final answer received. Concluding.")
                updateLastMessageDuration(durationMs)
                break
            } else {
                val toolCall = Util.parseToolCall(finalResponse, tools.keys)

                if (toolCall != null) {
                    val tool = tools[toolCall.name]
                    if (tool != null) {
                        Log.d(
                            "AgentDebug",
                            "Tool Call Detected: ${toolCall.name} with args: ${toolCall.args}"
                        )
                        // Display a user-friendly version of the tool call
                        updateLastMessage(
                            "Tool Call: ${toolCall.name}(${
                                toolCall.args.map { "${it.key}=${it.value}" }.joinToString()
                            })"
                        )
                        updateLastMessageDuration(durationMs)

                        // Execute the tool with the parsed arguments
                        val result = tool.execute(application, toolCall.args)
                        addMessage(result, Sender.TOOL)
                        addMessage("", Sender.AGENT)
                    } else {
                        // This handles the case where the model hallucinates a tool name.
                        val errorMsg = "Error: Model tried to call unknown tool '${toolCall.name}'"
                        updateLastMessage(errorMsg)
                        break
                    }
                } else {
                    // No tool call detected, this is a direct answer.
                    updateLastMessage(finalResponse)
                    updateLastMessageDuration(durationMs)
                    Log.d("AgentDebug", "No tool call detected. Model gave a direct answer.")
                    break
                }
            }
            currentTurn++
        }
    }

    // --- State Update Helpers ---

    private fun addMessage(text: String, type: Sender) {
        val message = ChatMessage(messageIdCounter.getAndIncrement(), text, type)
        _messages.update { currentList -> currentList + message }
    }

    private fun updateLastMessage(updatedText: String) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            val updatedMessage = lastMessage.copy(text = updatedText)
            currentList.dropLast(1) + updatedMessage
        }
    }

    private fun updateLastMessageDuration(durationMs: Long) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            if (lastMessage.type == Sender.AGENT) {
                val updatedMessage = lastMessage.copy(durationMs = durationMs)
                currentList.dropLast(1) + updatedMessage
            } else {
                currentList
            }
        }
    }

    private fun detectModelFamily(path: String): ModelFamily {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("gemma") -> ModelFamily.GEMMA2
            lowerPath.contains("llama") -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }

    private fun buildPromptWithHistory(
        history: List<ChatMessage>,
        isFinalAnswerTurn: Boolean
    ): String {
        return when (currentModelFamily) {
            ModelFamily.LLAMA3 -> buildLlama3Prompt(history)
            ModelFamily.GEMMA2 -> {
                if (isFinalAnswerTurn) {
                    buildGemma2FinalAnswerPrompt(history)
                } else {
                    buildGemma2Prompt(history)
                }
            }

            else -> history.lastOrNull { it.type == Sender.USER }?.text ?: ""
        }
    }

    private fun buildGemma2Prompt(history: List<ChatMessage>): String {
        // Find if the last message was a tool result to decide which prompt to use
        val isFinalAnswerTurn = history.lastOrNull()?.type == Sender.AGENT &&
            history.getOrNull(history.size - 2)?.type == Sender.TOOL

        if (isFinalAnswerTurn) {
            // If it's the final answer turn, use the simple synthesis prompt
            val userQuestion = history.findLast { it.type == Sender.USER }?.text ?: ""
            val toolResult = (history.findLast { it.type == Sender.TOOL }?.text ?: "")
                .replace(Regex("\\[Tool Result for [a-zA-Z_]+]:"), "")
                .trim()

            return """
You are a helpful assistant.
Use the following information to answer the user's question in a single, friendly sentence.

Information: $toolResult
Question: $userQuestion
Answer:
            """.trimIndent()
        } else {
            // Otherwise, build the full tool-selection prompt
            val promptBuilder = StringBuilder()
            val toolsAsJson = tools.values.joinToString(",\n") { tool ->
                """  { "name": "${tool.name}", "description": "${
                    tool.description.replace(
                        "\"",
                        "\\\""
                    )
                }" }"""
            }

            val systemInstruction = """
You are a helpful assistant with access to the following tools:
[$toolsAsJson]

To use a tool, respond with a single `<tool_call>` XML tag containing a JSON object with the tool's 'name' and 'args'.
If no tool is needed, answer the user's question directly.

EXAMPLE:
user: What is the weather like in Paris?
model: <tool_call>{"name": "get_weather", "args": {"city": "Paris"}}</tool_call>
            """.trimIndent()

            promptBuilder.append(systemInstruction)
            promptBuilder.append("\n\n**CONVERSATION:**\n")
            history.takeLast(4).forEach { message ->
                when (message.type) {
                    Sender.USER -> promptBuilder.append("user: ${message.text}\n")
                    Sender.AGENT -> if (message.text.isNotBlank()) promptBuilder.append("model: ${message.text}\n")
                    else -> {}
                }
            }
            promptBuilder.append("model: ")
            return promptBuilder.toString()
        }
    }

    private fun buildGemma2FinalAnswerPrompt(history: List<ChatMessage>): String {
        val userQuestion = history.findLast { it.type == Sender.USER }?.text ?: ""
        val toolResult = (history.findLast { it.type == Sender.TOOL }?.text ?: "")
            .replace("[Tool Result for get_current_datetime]:", "") // Keep this cleanup
            .trim()

        val finalPrompt = """
You are a helpful assistant.
Use the following information to answer the user's question.
Answer in a single, friendly sentence.

Information: $toolResult
Question: $userQuestion
Answer:
    """.trimIndent()

        return finalPrompt
    }

    private fun buildLlama3Prompt(history: List<ChatMessage>): String {
        val historyBuilder = StringBuilder()
        historyBuilder.append("<|begin_of_text|>")
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt<|eot_id|>")
        for (message in history) {
            when (message.type) {
                Sender.USER -> historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                Sender.AGENT -> {
                    if (message.text.isNotBlank()) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}")
                    }
                }

                Sender.SYSTEM -> {}
                Sender.TOOL -> {
                    historyBuilder.append("<|start_header_id|>tool<|end_header_id|>\n")
                    historyBuilder.append(message.text)
                    historyBuilder.append("<|eot_id|>\n")
                }
            }
        }
        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return historyBuilder.toString()
    }

    suspend fun cleanup() {
        try {
            engine.unloadModel()
            loadedModelPath = null
            Log.d(tag, "LLamaAndroid resources unloaded successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Error during LLamaAndroid unload", e)
        }
    }
}
