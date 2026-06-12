package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object AttributeKeyPatterns {
    /** Normalizes OCR confusions that often turn `im`/`m` into similar letter pairs. */
    val OCR_IM_OR_M_CONFUSION = Regex("inm|rn|wm|nm")

    /** Fixes OCR variants of the `layout` key prefix. */
    val LAYOUT_OCR_KEY = Regex("lay[ao0]ut")

    /** Converts OCR variants like `ld` or `td` to the `id` key token. */
    val OCR_ID_KEY = Regex("(?<=^|_)[lt]d(?=$|_)")

    /** Matches spaced OCR variants of the text color attribute key. */
    const val TEXT_COLOR_SPACED = "\\btext\\s*[-_ ]\\s*(?:colou?r|calar|colar)\\b"
    /** Matches compact OCR variants of the text color attribute key. */
    const val TEXT_COLOR_COMPACT_OCR = "\\btexteolou?r\\b|\\btexteolor\\b"
    /** Matches OCR variants of the input type attribute key. */
    const val INPUT_TYPE = "\\binput\\s*[-_ ]?\\s*type\\b"
    /** Matches OCR text split as text pass word. */
    const val TEXT_PASS_WORD = "\\btext\\s+pass\\s*word\\b"
    /** Matches spaced OCR text password values. */
    const val TEXT_PASSWORD = "\\btext\\s+password\\b"
    /** Matches OCR variants of the layout gravity attribute key. */
    const val LAYOUT_GRAVITY_OCR = "\\blay(?:out|aut)[_\\- ]?gr(?:av|a)ity\\b"
    /** Matches compact layout gravity text without a separator. */
    const val LAYOUT_GRAVITY_COMPACT = "\\blayoutgravity\\b"
    /** Matches OCR variants of the layout height attribute key. */
    const val LAYOUT_HEIGHT_OCR = "\\blayout[_\\- ]*heiqht\\b"
    /** Matches entries followed by an OCR-inserted i before a bracket. */
    const val ENTRIES_WITH_OCR_I_BEFORE_BRACKET = "\\bentries\\s*[iIl1]\\s*(?=\\[)"
    /** Matches entries before a bracket with an optional separator. */
    const val ENTRIES_BEFORE_BRACKET = "\\bentries\\s*[:=;]?\\s*(?=\\[)"
}
