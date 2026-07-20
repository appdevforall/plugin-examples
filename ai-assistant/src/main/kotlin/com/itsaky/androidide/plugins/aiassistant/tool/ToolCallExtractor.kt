package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import org.json.JSONObject

/**
 * Extracts tool calls from LLM responses using multiple strategies:
 * 1. Explicit XML tags: <tool_call>{"tool":"name",...}</tool_call>
 * 2. JSON blocks: {"tool":"name",...}
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

            Log.d(TAG, "Extracting tool calls from response (${text.length} chars)")
            Log.d(TAG, "Response preview: ${text.take(300)}")

            // Strategy 1: Explicit XML tags
            toolCalls.addAll(extractFromXmlTags(text))

            // Strategy 2: Bare JSON objects if no XML found
            if (toolCalls.isEmpty()) {
                toolCalls.addAll(extractFromJsonObjects(text))
            }

            Log.d(TAG, "Extracted ${toolCalls.size} tool calls from response (${text.length} chars)")

            // Warn if we found incomplete tool calls
            if (text.contains("<tool_call>") && text.count { it == '<' } > text.count { it == '>' }) {
                Log.w(TAG, "WARNING: Found incomplete tool call tags in response. Response may have been truncated.")
                Log.w(TAG, "Full response: $text")
            }

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
         * Uses brace-balanced extraction to handle nested args objects.
         */
        private fun extractFromJsonObjects(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()
            var found = 0

            // Find JSON objects with balanced braces containing "tool" field
            var i = 0
            while (i < text.length) {
                if (text[i] == '{') {
                    // Try to extract a balanced JSON object
                    var braceCount = 0
                    var j = i
                    var hasToolField = false

                    while (j < text.length) {
                        if (text[j] == '{') braceCount++
                        else if (text[j] == '}') braceCount--

                        // Check if this substring contains "tool"
                        if (!hasToolField && text.substring(i, minOf(j + 1, text.length)).contains("\"tool\"")) {
                            hasToolField = true
                        }

                        j++

                        if (braceCount == 0) {
                            // Found complete object
                            if (hasToolField) {
                                val jsonStr = text.substring(i, j)
                                val parsed = parseToolJson(jsonStr)
                                if (parsed != null) {
                                    toolCalls.add(parsed)
                                    found++
                                }
                            }
                            break
                        }
                    }

                    i = j
                } else {
                    i++
                }
            }

            Log.d(TAG, "Strategy 2 (JSON objects): Found $found matches")

            return toolCalls
        }

        /**
         * Parse tool JSON and extract tool name and arguments.
         */
        private fun parseToolJson(jsonStr: String): ToolCall? {
            return try {
                val json = JSONObject(jsonStr)
                val toolName = json.optString("tool").ifEmpty { json.optString("name") }
                if (toolName.isEmpty()) {
                    Log.w(TAG, "Tool JSON has neither 'tool' nor 'name': $jsonStr")
                    return null
                }
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
    }
}
