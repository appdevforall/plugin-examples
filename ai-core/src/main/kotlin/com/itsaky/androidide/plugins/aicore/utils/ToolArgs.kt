package com.itsaky.androidide.plugins.aicore.utils

/** Argument values a model uses to mean "true"; the tool-call grammar has no boolean type. */
private val TRUE_WORDS = setOf("true", "yes", "1")

/**
 * Reads a boolean out of a tool-call argument, tolerating the strings a model emits instead
 * (`"true"`, `"yes"`, `"1"`, any casing). Shared so the dialog and the handler cannot disagree about
 * `replace_all`; the no-argument [String.lowercase] keeps `"TRUE"` matching under tr-TR.
 * @param value the raw argument value, or null when absent.
 * @return true only for an explicit affirmative; false for null and anything else.
 */
fun parseToolBoolean(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    else -> value.toString().trim().lowercase() in TRUE_WORDS
}
