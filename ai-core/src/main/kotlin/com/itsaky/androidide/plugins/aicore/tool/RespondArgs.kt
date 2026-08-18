package com.itsaky.androidide.plugins.aicore.tool

/**
 * Keys a model uses for the `respond` payload, in preference order. `message` is what both
 * system prompts document; the rest are what models substitute anyway, and a `respond` call
 * whose text is under the wrong key is a finished answer that would otherwise be discarded.
 */
private val RESPOND_MESSAGE_KEYS = listOf("message", "text", "response", "answer", "content")

/**
 * Reads the user-facing answer out of a `respond` call's arguments, tolerating the keys a model
 * substitutes for `message`. The same tolerance the handlers get from `ToolHandler.argAliases`,
 * which `respond` never had because it has no handler.
 * @param args the `respond` call's arguments.
 * @return the answer, or null when no key carries usable text.
 */
fun respondMessageOf(args: Map<String, Any?>): String? =
    RESPOND_MESSAGE_KEYS.firstNotNullOfOrNull { key ->
        args[key]?.toString()?.takeIf { it.isNotBlank() }
    }
