package com.itsaky.androidide.plugins.aicore.tool

import android.util.Log
import org.json.JSONObject

/**
 * Extracts tool calls from an LLM reply, by explicit `<tool_call>` envelope first and bare
 * `{"tool":...}` JSON second. Works for both the cloud and local backends.
 */
class ToolCallExtractor {
    companion object {
        private const val TAG = "ToolCallExtractor"

        /** The `<tool_call>{…}</tool_call>` envelope both system prompts ask for. */
        private val TOOL_CALL_REGEX =
            Regex("""<tool_call>\s*(.+?)\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)

        /**
         * The prose left once the tool-call envelopes are removed. Worth showing when a `respond`
         * call carries no message, which usually means the model wrote the answer as prose and
         * emitted an empty envelope beside it.
         * @param text the model's raw reply.
         * @return the prose, or null when there is none, or when what remains is a bare
         *   (untagged) tool call rather than something meant for the user to read.
         */
        fun proseOutsideToolCalls(text: String): String? {
            val remainder = TOOL_CALL_REGEX.replace(text, "\n").trim()
            if (remainder.isEmpty()) return null
            // A leftover `"tool"` key is an unenveloped call; raw JSON is worse than nothing.
            if (remainder.contains("\"tool\"")) return null
            return remainder
        }

        /**
         * Extracts every tool call from [text], trying each strategy in turn.
         * @param text the model's raw reply.
         * @return the calls found, in the order they appear; empty when there are none.
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
            val matches = TOOL_CALL_REGEX.findAll(text)

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

            // Find JSON objects with balanced braces containing "tool" field.
            var i = 0
            while (i < text.length) {
                if (text[i] == '{') {
                    // Extract a balanced object, ignoring braces inside string values.
                    var braceCount = 0
                    var j = i
                    var hasToolField = false
                    var inString = false
                    var escaped = false

                    while (j < text.length) {
                        val c = text[j]
                        if (inString) {
                            when {
                                escaped -> escaped = false
                                c == '\\' -> escaped = true
                                c == '"' -> inString = false
                            }
                        } else {
                            when (c) {
                                '"' -> inString = true
                                '{' -> braceCount++
                                '}' -> braceCount--
                            }
                        }

                        // Check if this substring contains "tool"
                        if (!hasToolField && text.substring(i, minOf(j + 1, text.length)).contains("\"tool\"")) {
                            hasToolField = true
                        }

                        j++

                        if (!inString && braceCount == 0) {
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
