package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object StructurePatterns {
    /** Matches horizontal gravity center text leaking into layout metadata. */
    const val HORIZONTAL_GRAVITY_CENTER_LAYOUT = "horizontal\\s+gravity\\s*:\\s*center\\s+layout"
    /** Matches OCR variants of the src attribute key. */
    const val SRC_KEY_OCR = "\\bS[ec][rt]\\b\\s*[:;]?"

    /** Matches any ID key alias followed by a key-value separator. */
    fun idKeyWithSeparator(idKeyPattern: String): String {
        return "\\b(?:$idKeyPattern)\\b\\s*[:;]"
    }
}
