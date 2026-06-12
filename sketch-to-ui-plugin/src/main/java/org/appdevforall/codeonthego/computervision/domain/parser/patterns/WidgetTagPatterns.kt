package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object WidgetTagPatterns {
    /** Removes a widget tag accidentally appended to text or hint OCR. */
    val TRAILING_WIDGET_TAG = Regex(
        "\\s+(?:[A-Z]{1,2}\\s+)?(?:B|P|D|T|C|R|SW|S)\\s*-\\s*[A-Z0-9_]+\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** Removes a repeated tag prefix before a trailing widget tag. */
    val TRAILING_REPEATED_WIDGET_PREFIX = Regex(
        "\\s+(?:B|P|D|T|C|R|SW|S)\\s+(?=(?:B|P|D|T|C|R|SW|S)\\s*-\\s*[A-Z0-9_]+\\s*$)",
        RegexOption.IGNORE_CASE
    )
}
