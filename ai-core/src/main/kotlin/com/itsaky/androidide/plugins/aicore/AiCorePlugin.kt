package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * AI Core Plugin providing LLM inference capabilities.
 * Implements LlmInferenceService and registers local LLM backend.
 */
class AiCorePlugin : IPlugin, DocumentationExtension {

    private lateinit var context: PluginContext
    private lateinit var llmService: LlmInferenceServiceImpl
    private lateinit var localBackend: LocalLlmBackend
    private lateinit var geminiBackend: GeminiBackend

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
            // Create LlmInferenceService
            llmService = LlmInferenceServiceImpl()

            // Register in SharedServices (accessible by all plugins)
            SharedServices.register(LlmInferenceService::class.java, llmService)
            context.logger.info("AiCorePlugin: Registered LlmInferenceService in SharedServices")

            // Create and register local LLM backend
            localBackend = LocalLlmBackend(context)
            llmService.registerBackend(localBackend)
            context.logger.info("AiCorePlugin: Registered local LLM backend")

            // Create and register Gemini API backend
            geminiBackend = GeminiBackend(context)
            llmService.registerBackend(geminiBackend)
            context.logger.info("AiCorePlugin: Registered Gemini API backend")

            return true
        } catch (e: Exception) {
            context.logger.error("AiCorePlugin: Activation failed", e)
            return false
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiCorePlugin: Deactivating plugin")

        try {
            // Unregister backends
            if (::llmService.isInitialized) {
                llmService.unregisterBackend("local")
                context.logger.info("AiCorePlugin: Unregistered local LLM backend")
                llmService.unregisterBackend("gemini")
                context.logger.info("AiCorePlugin: Unregistered Gemini API backend")
            }

            // Unregister from SharedServices
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

        // Cancel any ongoing generation
        if (::llmService.isInitialized) {
            llmService.cancelGeneration()
        }

        // Free the native model and cancel backend scopes so nothing leaks
        // when the plugin is unloaded.
        if (::localBackend.isInitialized) {
            localBackend.close()
            context.logger.info("AiCorePlugin: Released local LLM backend")
        }
        if (::geminiBackend.isInitialized) {
            geminiBackend.close()
            context.logger.info("AiCorePlugin: Released Gemini backend")
        }
    }

    override fun getTooltipCategory(): String = "plugin_ai_core"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_PLUGIN,
            summary = "AI Core provides the shared local and Gemini LLM backends used by the AI plugins.",
            detail = """
                <p><b>AI Core</b> is a headless plugin that registers the shared
                LLM inference service used by AI Assistant, Code Suggestions,
                Speech to Text, and Vector Search.</p>
                <p>Configure the backend and model from <b>AI Assistant → AI
                Settings</b>. Local models stay on-device; Gemini requests are
                sent to Google over HTTPS.</p>
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
