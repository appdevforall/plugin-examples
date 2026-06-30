package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import org.json.JSONObject

/**
 * Extracts tool calls from LLM responses using multiple strategies:
 * 1. Explicit XML tags: <tool_call>{"tool":"name",...}</tool_call>
 * 2. JSON blocks: {"tool":"name",...}
 * 3. Implicit actions: Detects from natural language patterns
 *
 * Works with both cloud (Gemini) and local LLMs.
 */
class ToolCallExtractor {
    companion object {
        private const val TAG = "ToolCallExtractor"

        /**
         * Extract all tool calls from response text using multiple strategies.
         */
        fun extractToolCalls(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()

            // Strategy 1: Explicit XML tags
            toolCalls.addAll(extractFromXmlTags(text))

            // Strategy 2: Bare JSON objects if no XML found
            if (toolCalls.isEmpty()) {
                toolCalls.addAll(extractFromJsonObjects(text))
            }

            // Strategy 3: Implicit actions from natural language
            if (toolCalls.isEmpty()) {
                toolCalls.addAll(extractImplicitActions(text))
            }

            Log.d(TAG, "Extracted ${toolCalls.size} tool calls from response (${text.length} chars)")
            return toolCalls
        }

        /**
         * Strategy 1: Extract explicit tool calls from XML tags.
         * Format: <tool_call>{"tool":"name","args":{...}}</tool_call>
         */
        private fun extractFromXmlTags(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()
            val regex = Regex("""<tool_call>\s*(.+?)\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
            val matches = regex.findAll(text)

            Log.d(TAG, "Strategy 1 (XML tags): Found ${matches.count()} matches")

            for (match in matches) {
                val parsed = parseToolJson(match.groupValues[1].trim())
                if (parsed != null) {
                    toolCalls.add(parsed)
                }
            }

            return toolCalls
        }

        /**
         * Strategy 2: Extract tool calls from bare JSON objects.
         * Format: {"tool":"name","args":{...}}
         */
        private fun extractFromJsonObjects(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()

            // Find JSON objects that contain "tool" field
            val regex = Regex("""\{[^{}]*"tool"[^{}]*\}""")
            val matches = regex.findAll(text)

            Log.d(TAG, "Strategy 2 (JSON objects): Found ${matches.count()} matches")

            for (match in matches) {
                val parsed = parseToolJson(match.value)
                if (parsed != null) {
                    toolCalls.add(parsed)
                }
            }

            return toolCalls
        }

        /**
         * Strategy 3: Extract implicit tool calls from natural language.
         * Detects action keywords and converts to tool calls.
         */
        private fun extractImplicitActions(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()
            val lowerText = text.lowercase()

            Log.d(TAG, "Strategy 3 (Implicit actions): Analyzing for action keywords")

            // Patterns for list_files
            if (matchesPattern(lowerText, listOf("list", "show"), listOf("file", "directory", "folder"))) {
                val directory = extractDirectory(text) ?: "."
                toolCalls.add(ToolCall("list_files", mapOf("directory" to directory)))
                Log.d(TAG, "Detected: list_files action")
            }

            // Patterns for read_file
            if (matchesPattern(lowerText, listOf("read", "open", "view", "show"), listOf("file"))) {
                val path = extractFilePath(text)
                if (path != null) {
                    toolCalls.add(ToolCall("read_file", mapOf("path" to path)))
                    Log.d(TAG, "Detected: read_file action for $path")
                }
            }

            // Patterns for search_project
            if (matchesPattern(lowerText, listOf("search", "find", "grep"), listOf("file", "code", "project"))) {
                val query = extractSearchQuery(text)
                if (query != null) {
                    toolCalls.add(ToolCall("search_project", mapOf("query" to query)))
                    Log.d(TAG, "Detected: search_project action for query: $query")
                }
            }

            // Patterns for create_file
            if (matchesPattern(lowerText, listOf("create", "write", "make"), listOf("file"))) {
                // Requires more context, generally not auto-triggered
                Log.d(TAG, "Detected: create_file action (requires confirmation)")
            }

            // Patterns for run_app
            if (matchesPattern(lowerText, listOf("run", "launch", "build", "start"), listOf("app", "application"))) {
                toolCalls.add(ToolCall("run_app", emptyMap()))
                Log.d(TAG, "Detected: run_app action")
            }

            return toolCalls
        }

        /**
         * Parse tool JSON and extract tool name and arguments.
         */
        private fun parseToolJson(jsonStr: String): ToolCall? {
            return try {
                val json = JSONObject(jsonStr)
                val toolName = json.optString("tool") ?: json.optString("name") ?: return null
                val argsJson = json.optJSONObject("args") ?: json.optJSONObject("arguments") ?: JSONObject()

                val args = mutableMapOf<String, Any?>()
                val keys = argsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    args[key] = argsJson.get(key)
                }

                Log.d(TAG, "Parsed tool call: $toolName with args: $args")
                ToolCall(toolName, args)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse tool JSON: $jsonStr", e)
                null
            }
        }

        /**
         * Check if text matches action pattern (verb + object).
         */
        private fun matchesPattern(text: String, verbs: List<String>, objects: List<String>): Boolean {
            val hasVerb = verbs.any { text.contains(it) }
            val hasObject = objects.any { text.contains(it) }
            return hasVerb && hasObject
        }

        /**
         * Extract directory path from natural language.
         */
        private fun extractDirectory(text: String): String? {
            // Look for patterns like "in src", "in ./src", "in root", etc.
            val patterns = listOf(
                Regex("""(?:in|from)\s+["`']?([^"`'\s]+)["`']?"""),
                Regex("""directory\s+["`']?([^"`'\s]+)["`']?"""),
                Regex("""folder\s+["`']?([^"`'\s]+)["`']?""")
            )

            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    val dir = match.groupValues[1]
                    if (dir.isNotEmpty() && !dir.contains("the")) {
                        return dir
                    }
                }
            }

            return null
        }

        /**
         * Extract file path from natural language.
         */
        private fun extractFilePath(text: String): String? {
            // Look for patterns like "MainActivity.kt", "src/main/MainActivity.kt", etc.
            val patterns = listOf(
                Regex("""["`']([^"`'\s]+\.kt)["`']"""),
                Regex("""file\s+["`']?([^"`'\s]+\.kt)["`']?"""),
                Regex("""read\s+["`']?([^"`'\s]+)["`']?""")
            )

            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    val path = match.groupValues[1]
                    if (path.isNotEmpty() && !path.contains("the")) {
                        return path
                    }
                }
            }

            return null
        }

        /**
         * Extract search query from natural language.
         */
        private fun extractSearchQuery(text: String): String? {
            val patterns = listOf(
                Regex("""(?:search|find|grep)\s+(?:for\s+)?["`']([^"`']+)["`']"""),
                Regex("""search\s+(?:for\s+)?([^\.?!]+)""")
            )

            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    val query = match.groupValues[1].trim()
                    if (query.isNotEmpty() && query.length > 2) {
                        return query
                    }
                }
            }

            return null
        }
    }
}
