package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object GravityPatterns {
    /** Matches compact `centerhorizontal` gravity OCR. */
    val CENTER_HORIZONTAL_COMPACT = Regex("centerhorizontal")

    /** Matches compact `centervertical` gravity OCR. */
    val CENTER_VERTICAL_COMPACT = Regex("centervertical")

    /** Matches spaced `center horizontal` gravity OCR. */
    val CENTER_HORIZONTAL_SPACED = Regex("center\\s+horizontal")

    /** Matches spaced `center vertical` gravity OCR. */
    val CENTER_VERTICAL_SPACED = Regex("center\\s+vertical")

    /** Removes characters that cannot be part of normalized gravity tokens. */
    val GRAVITY_UNSAFE_CHARS = Regex("[^a-z_]+")

    /** Finds a whole gravity value without matching partial words. */
    fun wholeGravityValue(value: String): Regex {
        return Regex("(^|\\s)${Regex.escape(value)}(\\s|$)")
    }
}
