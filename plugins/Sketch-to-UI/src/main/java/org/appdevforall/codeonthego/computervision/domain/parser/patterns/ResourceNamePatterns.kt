package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object ResourceNamePatterns {
    /** Removes characters that are unsafe in Android resource names. */
    val RESOURCE_NAME_UNSAFE_CHARS = Regex("[^a-z0-9_]")

    /** Removes non-lowercase-alphanumeric characters for compact key checks. */
    val NON_ALPHANUMERIC_LOWER = Regex("[^a-z0-9]")
}
