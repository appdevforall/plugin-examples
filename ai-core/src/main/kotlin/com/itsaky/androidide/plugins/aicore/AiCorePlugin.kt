package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * AI Core Plugin providing LLM inference routing.
 *
 * Owns no backend: it publishes [LlmInferenceServiceImpl] to [SharedServices], and backend plugins
 * (ai-backend-local, ai-backend-gemini, …) register themselves with it on their own activation.
 */
class AiCorePlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext
    private lateinit var llmService: LlmInferenceServiceImpl

    companion object {
        const val PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
        private const val TOOLTIP_TAG_PLUGIN = "plugin_ai_core"
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("AiCorePlugin: Plugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Plugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("AiCorePlugin: Activating plugin")

        try {
            llmService = LlmInferenceServiceImpl(context.logger)

            // Register in SharedServices (accessible by all plugins). Backend plugins that
            // activated before this point re-register when they see AI Core activate.
            SharedServices.register(LlmInferenceService::class.java, llmService)
            context.logger.info("AiCorePlugin: Registered LlmInferenceService in SharedServices")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Activation failed", e)
            return false
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiCorePlugin: Deactivating plugin")

        try {
            // Backends belong to their own plugins; unregistering the service drops the whole
            // registry, and each backend plugin unregisters itself on its own deactivation.
            SharedServices.unregister(LlmInferenceService::class.java)
            context.logger.info("AiCorePlugin: Unregistered LlmInferenceService from SharedServices")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Deactivation failed", e)
            return false
        }
    }

    override fun dispose() {
        context.logger.info("AiCorePlugin: Disposing plugin")

        // Cancel any ongoing generation. Freeing backend resources (native models, HTTP scopes)
        // is each backend plugin's own dispose().
        if (::llmService.isInitialized) {
            llmService.cancelGeneration()
        }
    }

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "AI Core routes LLM requests to whichever backend plugins are installed.",
            detail = """
                <p><b>AI Core</b> is a headless plugin that registers the shared
                LLM inference service used by AI Assistant, Code Suggestions,
                Speech to Text, and Vector Search.</p>
                <p>It provides no models of its own. Install at least one backend
                plugin — <b>AI Local Backend</b> for on-device <code>.gguf</code>
                models, <b>AI Gemini Backend</b> for Google's API — then choose the
                backend and model from <b>AI Assistant → AI Settings</b>.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "AI Core guide",
                    uri = "index.html",
                    order = 0
                )
            )
        )
    )

    override fun getTier3DocsAssetPath(): String = "docs"
}
