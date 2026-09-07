package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object ColorPatterns {
    /** Removes non-letter/underscore characters before fuzzy color matching. */
    val NON_LETTER_OR_UNDERSCORE = Regex("[^a-z_]")

    /** Detects color literal values by their hash prefix. */
    const val COLOR_HEX_PREFIX = "#"

    /** Detects Android resource references by their at-sign prefix. */
    const val RESOURCE_REFERENCE_PREFIX = "@"

    /** Matches merged OCR text for background red with an inserted i. */
    const val BACKGROUND_I_RED = "backgroundired"
    /** Matches merged OCR text for background red. */
    const val BACKGROUND_RED = "backgroundred"
    /** Matches OCR variants of the background color attribute key. */
    const val BACKGROUND_KEY_OCR = "\\bback[a-z]*[-_.]?\\s*[:;]\\s*"
}
