package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object InputTypePatterns {
    /** Removes characters that cannot be part of an Android inputType value list. */
    val INPUT_TYPE_UNSAFE_CHARS = Regex("[^A-Za-z|]+")
}
