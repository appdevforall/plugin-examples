package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object TextPatterns {
    /** Removes non-letters when checking compact password-like OCR text. */
    val NON_LETTERS = Regex("[^a-z]+")

    /** Matches explicit text metadata fragments without consuming the next pipe-delimited fragment. */
    val EXPLICIT_TEXT_FRAGMENT = Regex("(?:^|\\|)\\s*text\\s*[:;]\\s*([^|]+)", RegexOption.IGNORE_CASE)

    /** Matches explicit hint metadata fragments without consuming the next pipe-delimited fragment. */
    val EXPLICIT_HINT_FRAGMENT = Regex("(?:^|\\|)\\s*hint\\s*[:;]\\s*([^|]+)", RegexOption.IGNORE_CASE)

    /** Detects metadata key leakage inside text recovered from an EditText. */
    val EDIT_TEXT_METADATA_LEAKAGE = Regex("\\b(?:layout|inputtype|textpassword)\\b", RegexOption.IGNORE_CASE)

    /** Matches OCR variants of the text style attribute key. */
    const val TEXT_STYLE_OCR = "text\\s*st[yj]l?e?"
}
