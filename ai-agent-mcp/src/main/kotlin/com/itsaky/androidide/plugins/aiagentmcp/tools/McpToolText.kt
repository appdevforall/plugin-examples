package com.itsaky.androidide.plugins.aiagentmcp.tools

/**
 * Sanitises the text an MCP server supplies before it leaves this plugin.
 *
 * Tool names and descriptions are untrusted remote strings that end up verbatim in a system prompt
 * assembled inside a third-party backend plugin. A newline is enough to forge prompt structure
 * there, and a name outside `[a-z0-9_]` is enough to make a tool the model can read but never call.
 */
object McpToolText {

    /** Max characters kept from a server label used as a tool-name prefix. */
    private const val MAX_PREFIX_LENGTH = 10

    /** Max characters kept from a tool name; the agent caps the namespaced form again. */
    private const val MAX_NAME_LENGTH = 24

    /** Max characters kept from a description; the agent's prompt budget caps it again, lower. */
    const val MAX_DESCRIPTION_LENGTH = 200

    /** Numbered variants tried for a name two tools truncated onto; past this the tool is dropped. */
    private const val MAX_VARIANTS = 20

    /**
     * The exposed name for one remote tool: a short server prefix plus the tool's own name.
     * @param serverName the user's label for the server.
     * @param toolName the tool's own name.
     * @return the exposed name, or null when nothing usable survives sanitising.
     */
    fun exposedName(serverName: String, toolName: String): String? {
        val tool = identifier(toolName).take(MAX_NAME_LENGTH).trim('_')
        if (tool.isEmpty()) return null
        val prefix = identifier(serverName).take(MAX_PREFIX_LENGTH).trim('_')
        return if (prefix.isEmpty()) tool else "${prefix}_$tool"
    }

    /**
     * A free variant of [name], numbered when two tools ended up sharing one exposed name.
     *
     * Truncation is what makes this necessary: `get_pull_request_comments` and
     * `get_pull_request_committers` are distinct tools that both survive [MAX_NAME_LENGTH] as the
     * same string, and dropping the second would silently take away a tool the user switched on.
     *
     * The numbered form still fits the agent's own cap: the longest name this builds is
     * [MAX_PREFIX_LENGTH] + [MAX_NAME_LENGTH] + a two-digit suffix.
     *
     * @param name the name [exposedName] produced.
     * @param taken the names already published by this source.
     * @return the name to publish, or null when even the numbered forms are spoken for.
     */
    fun disambiguate(name: String, taken: Set<String>): String? {
        if (name !in taken) return name
        return (2..MAX_VARIANTS).map { "${name}_$it" }.firstOrNull { it !in taken }
    }

    /**
     * A description safe to put in a prompt.
     * @param description the server's own text.
     * @return the text, flattened to one line and capped.
     */
    fun description(description: String): String {
        val flattened = description
            .map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()
        return if (flattened.length > MAX_DESCRIPTION_LENGTH) {
            flattened.take(MAX_DESCRIPTION_LENGTH).trimEnd() + "…"
        } else {
            flattened
        }
    }

    /** Lowercases and reduces to `[a-z0-9_]`, collapsing runs of separators into one `_`. */
    private fun identifier(raw: String): String {
        val mapped = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        return buildString {
            for (char in mapped) {
                if (char == '_' && endsWith("_")) continue
                append(char)
            }
        }.trim('_')
    }
}
