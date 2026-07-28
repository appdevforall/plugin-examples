package com.itsaky.androidide.plugins.aiassistant

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.aiassistant.fragments.ChatFragment
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

        // Tags for the interactive controls on the AI Settings dialog (see AiSettingsFragment).
        const val TOOLTIP_TAG_SETTINGS_BACKEND = "ai_settings_backend"
        const val TOOLTIP_TAG_SETTINGS_LOCAL_MODEL = "ai_settings_local_model"
        const val TOOLTIP_TAG_SETTINGS_GEMINI_KEY = "ai_settings_gemini_key"

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

        // Migrate chat history and settings on first activation
        migrateDataIfNeeded()

        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AI Assistant Plugin deactivating...")
        return true
    }

    override fun dispose() {
        context.logger.info("AI Assistant Plugin disposing...")

        // Release the shared references set up in initialize() so the plugin's
        // PluginContext (and everything it holds) can be garbage-collected when
        // the plugin is unloaded.
        SharedServices.unregister(PluginContext::class.java)
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
            tag = TOOLTIP_TAG_SETTINGS_GEMINI_KEY,
            summary = "Enter your Google Gemini API key. It is stored only on this device.",
            detail = """
                <p>Paste a Gemini API key to enable the cloud backend. The key is
                kept in this plugin's private preferences on-device and is sent
                only to Google's API over HTTPS. Requests (your prompts and
                project context) leave the device when Gemini is selected.</p>
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
