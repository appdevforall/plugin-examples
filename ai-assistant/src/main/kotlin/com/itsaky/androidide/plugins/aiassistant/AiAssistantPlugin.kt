package com.itsaky.androidide.plugins.aiassistant

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.aiassistant.fragments.ChatFragment
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.PathGuard
import java.io.File

class AiAssistantPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null

    companion object {
        /** Must match `plugin.id` in AndroidManifest.xml — keys the host's plugin Context lookup
         *  used by [com.itsaky.androidide.plugins.base.PluginFragmentHelper.getPluginInflater]. */
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiassistant"

        /** Tooltip category for this plugin (strict `plugin_<pluginId>` convention); shared by the tab and the AI Settings screen. */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        const val TOOLTIP_TAG_TAB = "agent_chat_tab"

        // Tags for the interactive controls on the Agent chat screen (see ChatFragment).
        const val TOOLTIP_TAG_CONTEXT_FILES = "agent_context_files"
        const val TOOLTIP_TAG_CHAT_INPUT = "agent_chat_input"
        const val TOOLTIP_TAG_CHAT_SEND = "agent_chat_send"
        const val TOOLTIP_TAG_CHAT_MENU = "agent_chat_menu"

        // Tags for the controls rendered inside chat messages (see ChatAdapter).
        const val TOOLTIP_TAG_MESSAGE_RETRY = "agent_message_retry"
        const val TOOLTIP_TAG_MESSAGE_OPEN_SETTINGS = "agent_message_open_settings"
        const val TOOLTIP_TAG_SYSTEM_LOG = "agent_system_log"

        // Tags for the interactive controls on the AI Settings dialog (see AiSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_BACK = "ai_settings_back"
        const val TOOLTIP_TAG_SETTINGS_BACKEND = "ai_settings_backend"
        const val TOOLTIP_TAG_SETTINGS_LOCAL_MODEL = "ai_settings_local_model"
        const val TOOLTIP_TAG_SETTINGS_LOCAL_SHA = "ai_settings_local_model_sha"
        const val TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT = "ai_settings_simple_prompt"
        const val TOOLTIP_TAG_SETTINGS_GEMINI_KEY = "ai_settings_gemini_key"
        const val TOOLTIP_TAG_SETTINGS_GEMINI_MODEL = "ai_settings_gemini_model"

        @Volatile
        private var pluginContext: PluginContext? = null

        fun getContext(): PluginContext? = pluginContext
    }

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        pluginContext = context  // Store for ChatFragment access

        // Also store in SharedServices so ai-core can access preferences
        SharedServices.register(PluginContext::class.java, context)

        context.logger.info("AI Assistant Plugin initializing...")
        return true
    }

    override fun activate(): Boolean {
        // Get LlmInferenceService from SharedServices
        llmService = SharedServices.get(LlmInferenceService::class.java)

        if (llmService == null) {
            context.logger.warn("LlmInferenceService not available - LOCAL_LLM backend disabled")
            context.logger.warn("Install AI Core plugin to enable local LLM support")
        } else {
            context.logger.info("LlmInferenceService available from SharedServices")
        }

        PathGuard.setProjectRootProvider {
            try {
                context.services.get(IdeProjectService::class.java)
                    ?.getCurrentProject()?.rootDir?.absolutePath
            } catch (e: Exception) {
                context.logger.warn("Could not resolve project root from IdeProjectService", e)
                null
            }
        }

        // Migrate chat history and settings on first activation
        migrateDataIfNeeded()

        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AI Assistant Plugin deactivating...")
        PathGuard.setProjectRootProvider(null)
        return true
    }

    override fun dispose() {
        context.logger.info("AI Assistant Plugin disposing...")

        // Release the shared references set up in initialize() so the plugin's
        // PluginContext (and everything it holds) can be garbage-collected when
        // the plugin is unloaded.
        SharedServices.unregister(PluginContext::class.java)
        PathGuard.setProjectRootProvider(null)
        pluginContext = null
        llmService = null
    }

    // Register Agent tab
    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "agent_chat",
                title = "Agent",
                order = 100,
                fragmentFactory = { ChatFragment() },
                isEnabled = true,
                isVisible = true,
                tooltipTag = TOOLTIP_TAG_TAB
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> = emptyList()

    // --- DocumentationExtension: three-tier tooltip help for the Agent tab ---
    //
    //   Tier 1 = `summary`        (one-liner shown on long-press)
    //   Tier 2 = `detail`         (HTML paragraph behind "See More")
    //   Tier 3 = `buttons[].uri`  (offline HTML page served from
    //                              src/main/assets/docs/ at localhost)

    override fun getTooltipCategory(): String = TOOLTIP_CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_TAB,
            summary = "AI Agent: chat with an on-device or Gemini model that can read, search and edit your project.",
            detail = """
                <p>The <b>Agent</b> tab opens a chat assistant backed by the
                <b>AI Core</b> plugin. It can answer questions and run an
                agentic tool-loop over your project.</p>
                <p>Backends:</p>
                <ul>
                  <li><b>Local</b> — on-device inference via llama.cpp (select a
                      <code>.gguf</code> model in Settings).</li>
                  <li><b>Gemini</b> — Google's cloud API (needs an API key in
                      Settings; requests leave the device over HTTPS).</li>
                </ul>
                <p>File-editing tools are confined to the current project and
                ask for approval before writing.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "AI Assistant guide",
                    uri = "index.html",  // resolves to plugin/<id>/index.html
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CONTEXT_FILES,
            summary = "Attach project files so the agent sees their contents with your next message.",
            detail = """
                <p>Opens a picker rooted at the <b>currently open project</b>; you
                can browse subfolders but not above the project root, and the
                picker won't open at all when no project is open.</p>
                <p>Tap files to select them, <b>Toggle All</b> to select every file
                in the folder you're viewing, then <b>Add Selected</b>. Attached
                files appear as chips above the prompt — remove a chip to drop the
                file again.</p>
                <p>Contents are sent with your message, so on the Gemini backend
                they leave the device.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CHAT_INPUT,
            summary = "Type your request here — questions, or instructions to change the project.",
            detail = """
                <p>Ask a question ("what does this class do?") or give an
                instruction ("add a Room dependency"). Plain read-only requests
                such as <i>open</i>, <i>read</i>, <i>list</i> and <i>search</i>
                are recognised directly and run without going through the model,
                so they work on every backend.</p>
                <p>Anything that writes to the project asks for your approval
                first.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CHAT_SEND,
            summary = "Send the prompt — turns into Stop while the agent is working.",
            detail = """
                <p>Sends your message to the selected backend. It stays disabled
                until you type something.</p>
                <p>While the agent is thinking or running tools this same button
                becomes <b>Stop</b>: tapping it cancels the current turn, ends any
                in-progress reply and discards the remaining tool steps.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CHAT_MENU,
            summary = "Agent menu: open AI Settings or start a new chat session.",
            detail = """
                <p>Opens the Agent's overflow menu:</p>
                <ul>
                  <li><b>Settings</b> — choose the backend (Local or Gemini),
                      pick a model and manage your Gemini API key.</li>
                  <li><b>Clear chat</b> — starts a fresh session. The previous
                      conversation stays on disk in the plugin's own storage.</li>
                </ul>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MESSAGE_RETRY,
            summary = "Send that message again after a failed reply.",
            detail = """
                <p>Appears on a message whose reply failed — a dropped network
                request, a model that wasn't loaded, or a turn you stopped.</p>
                <p><b>Retry</b> re-sends the same prompt with the same attached
                context files; it does not add a new message to the conversation.
                If it keeps failing, check the backend and model under
                <b>Settings</b>.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MESSAGE_OPEN_SETTINGS,
            summary = "Jump to AI Settings to fix the problem this message reports.",
            detail = """
                <p>Shown when the agent could not run because it is not configured
                yet — no local model selected, or no Gemini API key saved.</p>
                <p>Opens <b>AI Settings</b> so you can choose a backend, pick a
                <code>.gguf</code> model or enter a key, then return and send your
                message again.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SYSTEM_LOG,
            summary = "Collapsed system log — tap to expand the agent's internal steps.",
            detail = """
                <p>System entries record what the agent did behind the scenes: the
                tools it ran, the files it touched and any errors the backend
                reported.</p>
                <p>They stay collapsed to keep the conversation readable — tap the
                header to expand or collapse one. They are part of the saved
                session, not messages sent to the model.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_BACK,
            summary = "Close AI Settings and return to the Agent chat.",
            detail = """
                <p>Closes this dialog. Every setting here is saved as you change
                it, so there is nothing to confirm — the Agent picks up the new
                backend and model as soon as you return.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_BACKEND,
            summary = "Choose which model powers the Agent: on-device Local (llama.cpp) or cloud Gemini.",
            detail = """
                <p>Selects the active inference backend:</p>
                <ul>
                  <li><b>Local</b> — runs a <code>.gguf</code> model entirely on
                      the device; nothing leaves the phone.</li>
                  <li><b>Gemini</b> — calls Google's cloud API over HTTPS; needs
                      an API key.</li>
                </ul>
                <p>The choice below changes which settings appear.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_LOCAL_MODEL,
            summary = "Pick a local .gguf chat model to run on-device.",
            detail = """
                <p>Browse for a <code>.gguf</code> model file to load with
                llama.cpp. Use a <b>chat/instruct</b> model — embedding-only
                models can't generate replies. Larger models are slower and use
                more memory; the file is copied into the app's private storage on
                first use.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_LOCAL_SHA,
            summary = "Optional SHA-256 of your .gguf file, checked when the model is loaded.",
            detail = """
                <p>Paste the expected SHA-256 hash of the model file. It is stored
                with the model path and compared on load, so a truncated download
                or a swapped file is reported instead of failing deep inside
                llama.cpp.</p>
                <p>Leave it empty to skip the check. The value is saved when the
                field loses focus.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_SIMPLE_PROMPT,
            summary = "Send small local models a plainer prompt with no tool instructions.",
            detail = """
                <p>Small on-device models (roughly 1B parameters and under) tend to
                ramble or echo the prompt when handed the full tool-calling system
                prompt. With this on they get a short, plain instruction instead.</p>
                <p>The trade-off: the model won't emit tool calls, so it answers
                questions but won't edit your project. The direct
                <i>open/read/list/search</i> commands still work either way. Turn
                it off for a larger instruct model.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_MODEL,
            summary = "Pick which Gemini model to call; Refresh lists the ones your key can access.",
            detail = """
                <p><b>Refresh Models</b> asks Google which models your API key can
                actually use and fills the list from the response. Until then the
                list shows a small built-in set of known-good defaults.</p>
                <p>Selecting a model saves it immediately. If a previously saved
                model has since been retired, refreshing moves you to the first
                model in the live list rather than leaving a name that returns
                404.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_KEY,
            summary = "Enter your Google Gemini API key. It is stored only on this device.",
            detail = """
                <p>Paste a Gemini API key to enable the cloud backend. The key is
                encrypted with a key held in this device's hardware-backed Android
                Keystore before it is written to this plugin's private preferences,
                and is sent only to Google's API over HTTPS. Requests (your prompts
                and project context) leave the device when Gemini is selected.</p>
                <p>Use the eye button to check what you typed, <b>Save</b> to store
                it, <b>Edit</b> to change it later and <b>Clear</b> to remove it
                from the device.</p>
                <p>If the Keystore entry is ever lost — clearing the app's data,
                for instance — the stored key can no longer be decrypted and must
                be re-entered here.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        )
    )

    /** Subdirectory under src/main/assets/ holding the Tier 3 offline docs. */
    override fun getTier3DocsAssetPath(): String = "docs"

    private fun migrateDataIfNeeded() {
        migrateChatHistory()
        migrateSettings()
    }

    private fun migrateChatHistory() {
        try {
            val appChatDir = File(context.getAppFilesDir(), "chat_sessions")
            val pluginChatDir = File(context.getPluginFilesDir(), "chat_sessions")

            if (appChatDir.exists() && !pluginChatDir.exists()) {
                context.logger.info("Migrating chat history from app to plugin storage")
                pluginChatDir.mkdirs()

                var migratedCount = 0
                appChatDir.listFiles()?.forEach { file ->
                    val targetFile = File(pluginChatDir, file.name)
                    if (!targetFile.exists()) {
                        file.copyTo(targetFile, overwrite = false)
                        migratedCount++
                    }
                }

                context.logger.info("Migrated $migratedCount chat session files")
                // Keep original files (don't delete)
            } else if (pluginChatDir.exists()) {
                context.logger.info("Chat history already migrated")
            }
        } catch (e: Exception) {
            context.logger.error("Failed to migrate chat history", e)
        }
    }

    private fun migrateSettings() {
        try {
            val appPrefs = context.getAppSharedPreferences("LlamaPrefs")
            if (appPrefs == null) {
                context.logger.info("App preferences not found, skipping settings migration")
                return
            }

            val pluginPrefs = context.getPluginSharedPreferences("AgentSettings")

            val PREF_KEY_AI_BACKEND = "ai_backend_preference"
            val PREF_KEY_LOCAL_MODEL_PATH = "local_llm_model_path"
            val PREF_KEY_LOCAL_MODEL_SHA256 = "local_llm_model_sha256"

            var migratedCount = 0

            // Migrate backend preference
            if (!pluginPrefs.contains(PREF_KEY_AI_BACKEND)) {
                val backend = appPrefs.getString(PREF_KEY_AI_BACKEND, null)
                if (backend != null) {
                    pluginPrefs.edit().putString(PREF_KEY_AI_BACKEND, backend).apply()
                    migratedCount++
                }
            }

            // Migrate model path
            if (!pluginPrefs.contains(PREF_KEY_LOCAL_MODEL_PATH)) {
                val modelPath = appPrefs.getString(PREF_KEY_LOCAL_MODEL_PATH, null)
                if (modelPath != null) {
                    pluginPrefs.edit().putString(PREF_KEY_LOCAL_MODEL_PATH, modelPath).apply()
                    migratedCount++
                }
            }

            // Migrate model SHA256
            if (!pluginPrefs.contains(PREF_KEY_LOCAL_MODEL_SHA256)) {
                val sha256 = appPrefs.getString(PREF_KEY_LOCAL_MODEL_SHA256, null)
                if (sha256 != null) {
                    pluginPrefs.edit().putString(PREF_KEY_LOCAL_MODEL_SHA256, sha256).apply()
                    migratedCount++
                }
            }

            if (migratedCount > 0) {
                context.logger.info("Migrated $migratedCount settings from app to plugin")
            } else {
                context.logger.info("Settings already migrated")
            }
        } catch (e: Exception) {
            context.logger.error("Failed to migrate settings", e)
        }
    }
}
