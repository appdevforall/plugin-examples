# Comparison: AI Assistant Plugin vs Main App Agent Implementation

## Overview

The main CodeOnTheGo app (stage branch) has a complete agent implementation with sophisticated architecture. The plugin version is a simplified subset focused on basic chat functionality.

## Architecture Differences

### Main App: Full Agentic Loop
Located in `/CodeOnTheGo/agent/` module:

1. **LocalAgenticRunner.kt** - Simplified workflow for local LLM
   - Single-call simplified workflow (not multi-step planning)
   - Tool selection based on query intent
   - Argument inference for missing parameters
   - History management with token budget
   - Designed to avoid "max steps" errors common with on-device models

2. **Executor.kt** - Tool execution framework
   - Parallel vs sequential execution for tool calls
   - Tool approval management with user dialogs
   - Required argument validation
   - Execution modes: Parallel (read-only tools) vs Sequential (write tools)

3. **Agent State Machine**
   ```kotlin
   sealed class AgentState {
       object Idle
       data class Initializing(val message: String)
       data class Thinking(val thought: String)
       data class Executing(val currentStepIndex: Int, val plan: Plan)
       data class AwaitingApproval(val id: ApprovalId, val toolName: String,
                                     val toolArgs: Map<String, Any?>, val reason: String)
       data class Error(val message: String)
   }
   ```

4. **Tool Router & Handlers**
   - Centralized tool routing system
   - Individual handlers for each tool:
     - `ReadFileHandler`, `SearchProjectHandler`, `ListFilesHandler`
     - `CreateFileHandler`, `UpdateFileHandler`
     - `AddDependencyHandler`, `GetBuildOutputHandler`
     - `RunAppHandler`, `GetWeatherHandler`, `GetBatteryHandler`
   - Parallel-safe tools vs sequential tools

### Plugin: Basic Chat
Located in `/plugin-examples/ai-assistant/ai-assistant-plugin/`:

- **ChatViewModel.kt** - Simple message flow
  - Basic streaming API integration
  - No tool execution
  - No agent state machine
  - Direct LLM inference only

- **No Executor, No Tools, No Approval**
  - Relies entirely on external llama inference service
  - No planning/critic/executor loop
  - No multi-step reasoning

## UI/UX Differences

### Main App ChatFragment Features

1. **Agent Status Display**
   ```xml
   <agentStatusContainer>
       <agentStatusMessage>  <!-- Shows current state -->
       <agentStatusTimer>    <!-- Shows step/total time -->
       <tokenUsageText>      <!-- Token usage percentage -->
   </agentStatusContainer>
   ```

2. **Context Selection**
   - File picker for adding context files
   - Image picker for vision features
   - Chip-based context display with remove buttons
   - Master prompt building with context injection

3. **Tool Testing UI**
   - Dialog to select and test individual tools
   - Dynamic form generation based on tool parameters
   - Parameter type inference (string, number, boolean)
   - Default values for file paths, offsets, limits

4. **Approval Dialogs**
   - User approval for sensitive tool operations
   - Options: Approve Once, Approve for Session, Deny
   - Displays tool name and arguments
   - Prevents unauthorized file modifications

5. **Chat History Management**
   - Multiple chat sessions
   - Session persistence across restarts
   - Session title auto-generation from first user message
   - Navigation to chat history fragment

6. **Copy/Share Features**
   - Copy entire chat transcript to clipboard
   - Share transcript as text file
   - Formatted with sender labels (User/Agent/Tool/System)

7. **Material Design Components**
   - `MaterialAlertDialogBuilder` for dialogs
   - `TextInputLayout` for inputs
   - `Chip` for context items
   - Toolbar with menu items

### Plugin ChatFragment Features

Currently implemented:
- Basic message display with RecyclerView
- Text input and send button
- Dots animation during generation (". " → ".. " → "... ")
- Settings dropdown menu (Clear chat, Settings)
- Markdown rendering with Markwon
- Long-press to copy messages

Missing compared to main app:
- ❌ Agent state indicators
- ❌ Token usage tracking
- ❌ Context selection
- ❌ Tool testing
- ❌ Approval dialogs
- ❌ Chat history/sessions
- ❌ Copy/share transcript
- ❌ Toolbar with proper menu
- ❌ Material Design components

## Data Models

### Main App
```kotlin
// AgentState.kt
sealed class AgentState {
    object Idle
    data class Initializing(val message: String)
    data class Thinking(val thought: String)
    data class Executing(val currentStepIndex: Int, val plan: Plan)
    data class AwaitingApproval(...)
    data class Error(val message: String)
}

// ChatMessage.kt
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: Sender,  // USER, AGENT, TOOL, SYSTEM, SYSTEM_DIFF
    val status: MessageStatus,  // SENT, COMPLETED, ERROR
    val timestamp: Long,
    val durationMs: Long?
)

// Plan.kt
data class Plan(
    val steps: List<Step>
)

data class Step(
    val description: String,
    val toolCalls: List<FunctionCall>
)
```

### Plugin
```kotlin
// ChatMessage.kt (simplified)
data class ChatMessage(
    val id: String,
    val text: String,
    val sender: Sender,  // USER, AGENT, SYSTEM
    val status: MessageStatus,  // LOADING, SENT, COMPLETED, ERROR
    val timestamp: Long,
    val durationMs: Long?
)

// No AgentState, No Plan, No Step
```

## Workflow Comparison

### Main App: Simplified Workflow (LocalAgenticRunner)

```
User Input
    ↓
Select Relevant Tools (based on keywords)
    ↓
Build Simplified Prompt (with tools JSON if applicable)
    ↓
Single LLM Call
    ↓
Parse Response
    ├─ Tool Call → Execute Tool → Show Result
    └─ Direct Response → Show to User
```

### Plugin: Direct Streaming

```
User Input
    ↓
Build LlmConfig (temp, maxTokens, systemPrompt)
    ↓
llmService.generateStreaming(...)
    ↓
StreamCallback.onToken() → Update UI with each token
    ↓
StreamCallback.onComplete() → Mark as completed
    ↓
StreamCallback.onError() → Show error message
```

## Resources & Strings

### Main App
- All strings externalized in `res/values/strings.xml`
- Examples:
  ```xml
  <string name="agent_images_selected">%d images selected</string>
  <string name="agent_select_tool_to_test">Select a tool to test</string>
  <string name="agent_approval_title">Approve %s?</string>
  <string name="agent_tokens_percentage">%d%%</string>
  <string name="copy_chat">Copy Chat</string>
  <string name="copy_chat_option_copy">Copy to Clipboard</string>
  <string name="copy_chat_option_share">Share as File</string>
  ```

### Plugin
- Hardcoded strings in code
- Examples:
  ```kotlin
  "Clear chat" // Should be R.string.clear_chat
  "Settings"   // Should be R.string.settings
  "Enter your message..." // Should be R.string.hint_enter_message
  ```

**Recommendation:** Extract all hardcoded strings to `strings.xml` to match main app pattern.

## Key Takeaways

### What Plugin Should Add

1. **Agent State Machine**
   - Add `AgentState` sealed class
   - Update ViewModel to track state transitions
   - Show state in UI (Initializing, Thinking, Executing)

2. **Simplified Workflow from LocalAgenticRunner**
   - Port the tool selection logic
   - Add argument inference
   - Implement simplified prompt building
   - Add tool execution framework

3. **Executor & Tools**
   - Create `Executor` class for tool execution
   - Implement basic tools (read_file, search_project, list_files)
   - Add tool approval mechanism
   - Support parallel vs sequential execution

4. **UI Enhancements**
   - Add agent status container (message + timer)
   - Add token usage display
   - Add context selection
   - Add chat history/sessions
   - Extract all strings to resources

5. **Material Design**
   - Use `MaterialAlertDialogBuilder` instead of `AlertDialog`
   - Use `TextInputLayout` for better input fields
   - Use `Chip` for context items
   - Use Material theming

### What Works Well in Plugin

1. **Streaming UI** - Token-by-token display with DiffUtil payloads
2. **Markdown Rendering** - Using Markwon for formatting
3. **Message Actions** - Long-press to copy, retry on error
4. **Dots Animation** - Simple loading indicator next to timestamp

## Recommendation

**Phase 1: Port LocalAgenticRunner Workflow**
- Copy `LocalAgenticRunner.kt` logic to plugin
- Implement simplified single-call workflow
- Add tool selection based on keywords
- Add argument inference

**Phase 2: Add Basic Tools**
- Implement `Executor.kt` for tool execution
- Add `ReadFileHandler`, `SearchProjectHandler`, `ListFilesHandler`
- Add tool approval dialogs

**Phase 3: UI Polish**
- Add agent state display
- Add Material Design components
- Extract all strings to resources
- Add context selection

**Phase 4: Advanced Features**
- Add chat history/sessions
- Add copy/share transcript
- Add tool testing UI
- Add token usage tracking

This phased approach allows incremental improvement while keeping the plugin functional at each stage.
