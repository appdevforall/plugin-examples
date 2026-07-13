package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object DimensionPatterns {
    /** Detects switch width OCR where `100dp` lost the leading one. */
    val ZERO_ZERO_DP = Regex("^0{2}\\s*dp$", RegexOption.IGNORE_CASE)

    /** Extracts the first signed integer from OCR text. */
    val SIGNED_INTEGER = Regex("-?\\d+")

    /** Extracts a leading signed integer from a cleaned dimension value. */
    val LEADING_SIGNED_INTEGER = Regex("^-?\\d+")

    /** Extracts the first signed decimal number from OCR text. */
    val SIGNED_DECIMAL = Regex("-?\\d+\\.?\\d*")

    /** Removes OCR variants of an `sp` suffix before text-size cleanup. */
    val SP_SUFFIX = Regex("(sp|5p)$")

    /** Counts OCR letters embedded inside dimension values, such as `52do`. */
    val DIMENSION_OCR_NOISE = Regex("(?<=\\d)[DOIl](?=\\s*(?:dp|de|do)\\b)", RegexOption.IGNORE_CASE)

    /** Detects an unlabeled dimension fragment adjacent to a labeled dimension. */
    val UNLABELED_DIMENSION = Regex("^[0-9]+\\s*(?:dp|de|do)$", RegexOption.IGNORE_CASE)

    /** Detects duplicated square dimensions in compact ImageView OCR. */
    val ADJACENT_REPEATED_DIMENSION = Regex(
        "\\b(?:width|height|layout_width|layout_height)\\s*:\\s*([0-9]+)\\s*(?:dp|de|do)(?:\\s+|\\s*\\|\\s*)\\1\\s*(?:dp|de|do)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Matches compact layout width OCR with optional unit. */
    val COMPACT_LAYOUT_WIDTH = Regex(
        "\\blay(?:out|aut)?[_\\-\\s]*w(?:idth)?[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )

    /** Matches compact layout height OCR with optional unit. */
    val COMPACT_LAYOUT_HEIGHT = Regex(
        "\\blay(?:out|aut)?[_\\-\\s]*h(?:ei(?:ght?)?)?[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )

    /** Matches compact bare width OCR with optional unit. */
    val COMPACT_BARE_WIDTH = Regex(
        "\\bwidth[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )

    /** Matches compact bare height OCR with optional unit. */
    val COMPACT_BARE_HEIGHT = Regex(
        "\\bheight[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )

    /** Detects supported dimension units in compact OCR fragments. */
    val COMPACT_DIMENSION_UNIT = Regex("(dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)

    /** Detects a reliable explicit dimension with a visible unit. */
    fun explicitDimensionWithUnit(aliases: String): Regex {
        return Regex("\\b$aliases\\s*[:;]\\s*[0-9oOIlLSBZz]+\\s*(?:dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)
    }

    /** Detects a reliable compact dimension with a visible unit. */
    fun compactDimensionWithUnit(aliases: String): Regex {
        return Regex("\\b$aliases[iIl1|]?[0-9oOIlLSBZz]+\\s*(?:dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)
    }

    /** Matches OCR variants of the layout width attribute key with common separators. */
    const val LAYOUT_WIDTH_OCR = "[il]ay[a-z]*[-_.\\s]*w[a-z0-9]*\\.?\\s*[:;.]\\s*"

    /** Matches OCR variants of the layout height attribute key with common separators. */
    const val LAYOUT_HEIGHT_OCR = "[il]ay[a-z]*[-_.\\s]*hei[a-z0-9]*\\.?\\s*[:;.]\\s*"
    /** Matches OCR variants of the match_parent dimension value. */
    const val MATCH_PARENT_OCR = "m?w?at[ce]h[-_\\s]?p[ar]+ent"
}
