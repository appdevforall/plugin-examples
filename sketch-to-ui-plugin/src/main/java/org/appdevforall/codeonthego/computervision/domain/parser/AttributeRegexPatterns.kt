package org.appdevforall.codeonthego.computervision.domain.parser

internal object AttributeRegexPatterns {
    val MULTIPLE_UNDERSCORES = Regex("_+")
    val OCR_IM_OR_M_CONFUSION = Regex("inm|rn|wm|nm")
    val RESOURCE_NAME_UNSAFE_CHARS = Regex("[^a-z0-9_]")
    val SPACES_BEFORE_COLON = Regex("\\s+:")
    val TOKEN_SPLIT = Regex("[:;]|\\s+")
    val WHITESPACE = Regex("\\s+")
    val LAYOUT_OCR_KEY = Regex("lay[ao0]ut")
    val OCR_ID_KEY = Regex("(?<=^|_)[lt]d(?=$|_)")
    val ZERO_ZERO_DP = Regex("^0{2}\\s*dp$", RegexOption.IGNORE_CASE)

    const val COLOR_HEX_PREFIX = "#"
    const val RESOURCE_REFERENCE_PREFIX = "@"
}
