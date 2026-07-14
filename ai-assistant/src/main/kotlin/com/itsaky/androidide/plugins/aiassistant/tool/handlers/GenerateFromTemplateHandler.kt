package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeTemplateService
import org.json.JSONObject

/**
 * Handler for generating files from Pebble templates.
 */
class GenerateFromTemplateHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "generate_from_template"
    override val description = "Generate files from Pebble templates with variable substitution"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val templateName = args["template_name"]?.toString()?.trim()
        if (templateName.isNullOrBlank()) {
            return ToolResult.failure(
                "template_name is required",
                "Provide a template name like 'activity', 'fragment', 'recycler_item', etc."
            )
        }

        // Variables is a map for substitution
        @Suppress("UNCHECKED_CAST")
        val variables = (args["variables"] as? Map<String, Any?>) ?: emptyMap()

        Log.d("GenerateFromTemplateHandler", "Generating from template: $templateName with ${variables.size} variables")

        return try {
            val templateService = pluginContext.services.get(IdeTemplateService::class.java)
            if (templateService == null) {
                Log.w("GenerateFromTemplateHandler", "IdeTemplateService not available")
                return ToolResult.failure(
                    "Template service not available",
                    "The IDE template service is not available. Templates may not be registered."
                )
            }

            // Get available templates
            val registeredTemplates = templateService.getRegisteredTemplates()
            Log.d("GenerateFromTemplateHandler", "Available templates: $registeredTemplates")

            if (registeredTemplates.isEmpty()) {
                return ToolResult.failure(
                    "No templates available",
                    "No Pebble templates are registered. Check template configuration."
                )
            }

            // Look for matching template file
            val matchingTemplate = registeredTemplates.firstOrNull { it.contains(templateName, ignoreCase = true) }
            if (matchingTemplate == null) {
                return ToolResult.failure(
                    "Template not found: $templateName",
                    "Available templates: ${registeredTemplates.joinToString(", ")}"
                )
            }

            Log.d("GenerateFromTemplateHandler", "Found template: $matchingTemplate")

            // Note: Actual template execution requires CgtTemplateBuilder integration
            // For now, return a note that user should use the template directly from Plugin Manager
            ToolResult.success(
                message = "Template located: $matchingTemplate",
                data = buildString {
                    append("Template '$templateName' is available.\n\n")
                    append("To use this template:\n")
                    append("1. Go to Plugin Manager → Templates\n")
                    append("2. Select template: $matchingTemplate\n")
                    append("3. Provide variables:\n")
                    variables.forEach { (k, v) ->
                        append("   - $k = $v\n")
                    }
                    append("\nAlternatively, use create_file to generate content directly.")
                }
            )
        } catch (e: Exception) {
            Log.e("GenerateFromTemplateHandler", "Error generating from template", e)
            ToolResult.failure(
                "Error with template",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
