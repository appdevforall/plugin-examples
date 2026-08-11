package com.itsaky.androidide.plugins.aiassistant

import android.content.res.Resources
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.PluginSettingsEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.SettingsExtension
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.aiassistant.fragments.AiSettingsFragment
import com.itsaky.androidide.plugins.aiassistant.fragments.ChatFragment
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.PathGuard
import java.io.File

class AiAssistantPlugin : IPlugin, UIExtension, DocumentationExtension, SettingsExtension {

    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null

    companion object {
        /** Must match `plugin.id` in AndroidManifest.xml — keys the host's plugin Context lookup
         *  used by [com.itsaky.androidide.plugins.base.PluginFragmentHelper.getPluginInflater]. */
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aiassistant"

        /**
         * Tooltip category for this plugin, in the strict `plugin_<pluginId>` form the host both
         * registers and resolves under; shared by the tab and the AI Settings screen.
         */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

        const val TOOLTIP_TAG_TAB = "agent_chat_tab"

        // Tags for the interactive controls on the Agent chat screen (see ChatFragment).
        const val TOOLTIP_TAG_CONTEXT_FILES = "agent_context_files"
        const val TOOLTIP_TAG_CHAT_INPUT = "agent_chat_input"
        const val TOOLTIP_TAG_CHAT_SEND = "agent_chat_send"
        const val TOOLTIP_TAG_CHAT_MENU = "agent_chat_menu"

        // Tags for the approval dialog: the consent gate, so every button carries its own help.
        const val TOOLTIP_TAG_APPROVAL_ACCEPT = "agent_approval_accept"
        const val TOOLTIP_TAG_APPROVAL_CORRECT = "agent_approval_correct"
        const val TOOLTIP_TAG_APPROVAL_DECLINE = "agent_approval_decline"
        const val TOOLTIP_TAG_APPROVAL_CORRECTION_INPUT = "agent_approval_correction_input"
        const val TOOLTIP_TAG_APPROVAL_RUN_NOW = "agent_approval_run_now"
        const val TOOLTIP_TAG_APPROVAL_ALWAYS_ALLOW = "agent_approval_always_allow"

        // Tags for the controls rendered inside chat messages (see ChatAdapter).
        const val TOOLTIP_TAG_MESSAGE_RETRY = "agent_message_retry"
        const val TOOLTIP_TAG_MESSAGE_OPEN_SETTINGS = "agent_message_open_settings"
        const val TOOLTIP_TAG_SYSTEM_LOG = "agent_system_log"

        // Tags for the interactive controls on the AI Settings screen (see AiSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_BACK = "ai_settings_back"
        const val TOOLTIP_TAG_SETTINGS_BACKEND = "ai_settings_backend"

        @Volatile
        private var pluginContext: PluginContext? = null

        fun getContext(): PluginContext? = pluginContext
    }

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        pluginContext = context  // Store for ChatFragment access

        // Also store in SharedServices so the backend plugins can access preferences
        SharedServices.register(PluginContext::class.java, context)

        context.logger.info("AI Assistant Plugin initializing...")
        return true
    }

    override fun activate(): Boolean {
        // Get LlmInferenceService from SharedServices
        llmService = SharedServices.get(LlmInferenceService::class.java)

        if (llmService == null) {
            context.logger.warn("LlmInferenceService not available - no backend can be reached")
            context.logger.warn("Install the AI Core plugin, plus at least one AI backend plugin")
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

        // Releases initialize()'s shared references so the PluginContext can be collected.
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

    // --- SettingsExtension: the Agent row in Preferences -> Configuration ---

    /**
     * One row, opening the same [AiSettingsFragment] the Agent chat's own shortcuts open. The host
     * calls this every time Preferences is built, so it stays cheap and free of side effects.
     */
    override fun getSettingsEntries(): List<PluginSettingsEntry> = listOf(
        PluginSettingsEntry(
            id = "agent_settings",
            title = string(R.string.pref_agent_title, "Agent"),
            summary = string(R.string.pref_agent_summary, "AI backend, model and API key"),
            fragmentClassName = AiSettingsFragment::class.java.name
        )
    )

    /**
     * Resolve [resId] against this plugin's own resources — [PluginContext.androidContext] is
     * plugin-scoped, so the plugin's strings.xml applies.
     *
     * @param fallback returned when the context is missing or the lookup fails; the host may build
     *   Preferences either side of a lifecycle edge and must never see an exception from here
     */
    private fun string(resId: Int, fallback: String): String =
        try {
            pluginContext?.androidContext?.getString(resId) ?: fallback
        } catch (e: Resources.NotFoundException) {
            pluginContext?.logger?.warn("AiAssistantPlugin: settings row string $resId missing", e)
            fallback
        }

    // --- DocumentationExtension: three-tier in-IDE help for the Agent tab ---

    /**
     * Three-tier in-IDE help: `summary` is Tier 1 (long-press one-liner), `detail` is Tier 2 (HTML
     * behind "See More") and `buttons[].uri` is Tier 3 (the offline page under assets/docs/).
     * @return the strict `plugin_<pluginId>` category the host registers and resolves under.
     */
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
                      <code>.gguf</code> model under <b>Preferences &rarr;
                      Configuration &rarr; Agent</b>).</li>
                  <li><b>Gemini</b> — Google's cloud API (needs an API key on the
                      same screen; requests leave the device over HTTPS).</li>
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
            summary = "Agent menu: open the Agent settings or start a new chat session.",
            detail = """
                <p>Opens the Agent's overflow menu:</p>
                <ul>
                  <li><b>Settings</b> — a shortcut to the same screen as
                      <b>Preferences &rarr; Configuration &rarr; Agent</b>: choose
                      the backend (Local or Gemini), pick a model and manage your
                      Gemini API key.</li>
                  <li><b>Clear chat</b> — starts a fresh session. The previous
                      conversation stays on disk in the plugin's own storage.</li>
                </ul>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_APPROVAL_ACCEPT,
            summary = "Apply the change shown above to the file, exactly as written.",
            detail = """
                <p>Applies the proposed edit. The block above shows the change:
                lines marked <code>-</code> are removed and lines marked
                <code>+</code> are put in their place — read them before
                accepting, because the agent proposed them, not you.</p>
                <p>A very long snippet is cut so the dialog stays readable. When
                that happens the block says so and how much is hidden — and the
                hidden part is still written. <b>Decline</b> any edit whose whole
                change you cannot see.</p>
                <p>If the file is open in the editor the change goes into that
                buffer, so <b>Ctrl+Z undoes it</b> and any unsaved work you had is
                preserved. If it isn't open, the file is rewritten on disk.</p>
                <p>Approval is asked for <b>every single edit</b> — there is no
                "always allow" for editing, so one tap never grants access to the
                rest of your project.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_APPROVAL_CORRECT,
            summary = "Reject this attempt but tell the agent what to do instead, so it retries.",
            detail = """
                <p>Use this when the edit is close but not right — the correct
                change in the wrong place, or the right idea with a name you don't
                want. It opens a box for a one-line instruction such as
                <i>"keep the original method name, only change the return type"</i>.</p>
                <p>Nothing is written. Your instruction goes back to the agent as
                the reason this call failed, so it can try again with that
                guidance — which is cheaper than declining and re-typing your whole
                request.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_APPROVAL_CORRECTION_INPUT,
            summary = "Describe what to change about the proposed edit; the agent retries with this.",
            detail = """
                <p>Write a short instruction in plain language — one sentence is
                usually enough. It is handed to the agent verbatim as the reason
                this edit was rejected.</p>
                <p><b>Send</b> returns the instruction and closes the approval
                prompt; <b>Back</b> leaves the proposed change on screen so you can
                still accept or decline it. Sending an empty box simply tells the
                agent to revise the edit without saying how.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_APPROVAL_DECLINE,
            summary = "Refuse this action; nothing is written and the agent is told you said no.",
            detail = """
                <p>Rejects the action outright. No file is touched.</p>
                <p>The agent is told the user denied the call, so it will normally
                stop rather than retry the same thing. Prefer <b>Correct</b> if you
                want it to keep working on the task with different details.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_APPROVAL_RUN_NOW,
            summary = "Allow this one tool call to run now.",
            detail = """
                <p>Runs the tool once, with the arguments shown above. Values are
                shortened for readability, so a long path or snippet may be cut —
                the full value is what actually runs.</p>
                <p>You'll be asked again the next time this tool is used, unless
                you choose <b>Always Allow</b>.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_APPROVAL_ALWAYS_ALLOW,
            summary = "Stop asking about this tool for the rest of this session.",
            detail = """
                <p>Approves this tool for the current session, so the agent can use
                it again without prompting. The grant is by <b>tool name only</b> —
                it does not depend on the arguments — and it is forgotten when the
                session ends.</p>
                <p>It is deliberately unavailable for file edits: those are
                re-confirmed every time, with the change on screen.</p>
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
                <b>Preferences &rarr; Configuration &rarr; Agent</b>.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MESSAGE_OPEN_SETTINGS,
            summary = "Jump to the Agent settings to fix the problem this message reports.",
            detail = """
                <p>Shown when the agent could not run because it is not configured
                yet — no local model selected, or no Gemini API key saved.</p>
                <p>Opens the <b>Agent</b> settings screen — the same one under
                <b>Preferences &rarr; Configuration &rarr; Agent</b> — so you can
                choose a backend, pick a <code>.gguf</code> model or enter a key,
                then return and send your message again.</p>
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
            summary = "Close the Agent settings and go back where you came from.",
            detail = """
                <p>Closes this screen, returning to Preferences or to the Agent
                chat depending on how you opened it. Every setting here is saved as
                you change it, so there is nothing to confirm — the Agent picks up
                the new backend and model as soon as you return.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SETTINGS_BACKEND,
            summary = "Choose which installed backend powers the Agent.",
            detail = """
                <p>Lists every AI backend plugin installed and registered with
                <b>AI Core</b> — for example <b>AI Local Backend</b> for
                on-device <code>.gguf</code> models, or <b>AI Gemini Backend</b>
                for Google's cloud API.</p>
                <p>Each backend supplies its own settings, so the panel below
                changes with the choice. If the list is empty, no backend is
                installed: add one from the Plugin Manager.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "AI Assistant guide", uri = "index.html", order = 0)
            )
        ),
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
