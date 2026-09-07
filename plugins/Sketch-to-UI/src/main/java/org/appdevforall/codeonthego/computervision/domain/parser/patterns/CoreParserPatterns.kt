package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object CoreParserPatterns {
    /** Collapses repeated underscores after OCR/resource-name cleanup. */
    val MULTIPLE_UNDERSCORES = Regex("_+")

    /** Removes whitespace before an OCR-detected key/value colon. */
    val SPACES_BEFORE_COLON = Regex("\\s+:")

    /** Splits non-delimited OCR annotations into likely key/value tokens. */
    val TOKEN_SPLIT = Regex("[:;]|\\s+")

    /** Normalizes any whitespace run to a single separator. */
    val WHITESPACE = Regex("\\s+")

    /** Extracts the key prefix from an annotation fragment before value comparison. */
    val ATTRIBUTE_KEY_PREFIX = Regex("^\\s*([a-zA-Z_\\- ]+?)(?=\\s*[:;]|\\d)")
}
