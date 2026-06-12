package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object ImageViewPatterns {
    /** Matches compact ImageView IDs like `imglogo` for drawable-backed normalization. */
    val COMPACT_IMAGE_ID = Regex("^img([a-z0-9]+)$")

    /** Matches ImageView IDs split into OCR tokens such as `im view 1`. */
    val IMAGE_VIEW_ID_TOKENS = Regex("\\b(?:i?m)[_\\s-]+view[_\\s-]+\\d+\\b", RegexOption.IGNORE_CASE)

    /** Matches compact ImageView IDs such as `imview1`. */
    val IMAGE_VIEW_ID_COMPACT = Regex("\\b(?:i?m)view\\d+\\b", RegexOption.IGNORE_CASE)
}
