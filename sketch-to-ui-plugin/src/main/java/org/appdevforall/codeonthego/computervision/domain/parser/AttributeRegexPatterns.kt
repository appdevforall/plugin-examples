package org.appdevforall.codeonthego.computervision.domain.parser

internal object AttributeRegexPatterns {
    val MULTIPLE_UNDERSCORES = Regex("_+")
    val OCR_IM_OR_M_CONFUSION = Regex("inm|rn|wm|nm")
    val RESOURCE_NAME_UNSAFE_CHARS = Regex("[^a-z0-9_]")
    val SPACES_BEFORE_COLON = Regex("\\s+:")
    val WHITESPACE = Regex("\\s+")
}
