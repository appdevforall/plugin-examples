package org.appdevforall.codeonthego.computervision.domain.parser

internal object AttributeRegexPatterns {
    /** Collapses repeated underscores after OCR/resource-name cleanup. */
    val MULTIPLE_UNDERSCORES = Regex("_+")

    /** Normalizes OCR confusions that often turn `im`/`m` into similar letter pairs. */
    val OCR_IM_OR_M_CONFUSION = Regex("inm|rn|wm|nm")

    /** Removes characters that are unsafe in Android resource names. */
    val RESOURCE_NAME_UNSAFE_CHARS = Regex("[^a-z0-9_]")

    /** Removes whitespace before an OCR-detected key/value colon. */
    val SPACES_BEFORE_COLON = Regex("\\s+:")

    /** Splits non-delimited OCR annotations into likely key/value tokens. */
    val TOKEN_SPLIT = Regex("[:;]|\\s+")

    /** Normalizes any whitespace run to a single separator. */
    val WHITESPACE = Regex("\\s+")

    /** Fixes OCR variants of the `layout` key prefix. */
    val LAYOUT_OCR_KEY = Regex("lay[ao0]ut")

    /** Converts OCR variants like `ld` or `td` to the `id` key token. */
    val OCR_ID_KEY = Regex("(?<=^|_)[lt]d(?=$|_)")

    /** Detects switch width OCR where `100dp` lost the leading one. */
    val ZERO_ZERO_DP = Regex("^0{2}\\s*dp$", RegexOption.IGNORE_CASE)

    /** Removes non-letters when checking compact password-like OCR text. */
    val NON_LETTERS = Regex("[^a-z]+")

    /** Removes non-lowercase-alphanumeric characters for compact key checks. */
    val NON_ALPHANUMERIC_LOWER = Regex("[^a-z0-9]")

    /** Removes non-letter/underscore characters before fuzzy color matching. */
    val NON_LETTER_OR_UNDERSCORE = Regex("[^a-z_]")

    /** Extracts the first signed integer from OCR text. */
    val SIGNED_INTEGER = Regex("-?\\d+")

    /** Extracts a leading signed integer from a cleaned dimension value. */
    val LEADING_SIGNED_INTEGER = Regex("^-?\\d+")

    /** Finds supported Android dimension units at the end of a value. */
    val DIMENSION_UNIT_SUFFIX = Regex("(dp|sp|px|in|mm|pt)$")

    /** Removes OCR variants of an `sp` suffix before text-size cleanup. */
    val SP_SUFFIX = Regex("(sp|5p)$")

    /** Removes common drawable file extensions before resource-name cleanup. */
    val DRAWABLE_EXTENSION_SUFFIX = Regex("\\.(png|jpg|jpeg|webp|xml|svg)$")

    /** Replaces a standalone OCR `im` token with `image` in drawable names. */
    val STANDALONE_IM_TOKEN = Regex("(^|_)im($|_)")

    /** Removes characters that cannot be part of an Android inputType value list. */
    val INPUT_TYPE_UNSAFE_CHARS = Regex("[^A-Za-z|]+")

    /** Extracts the first signed decimal number from OCR text. */
    val SIGNED_DECIMAL = Regex("-?\\d+\\.?\\d*")

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

    /** Extracts the key prefix from an annotation fragment before value comparison. */
    val ATTRIBUTE_KEY_PREFIX = Regex("^\\s*([a-zA-Z_\\- ]+?)(?=\\s*[:;]|\\d)")

    /** Counts OCR letters embedded inside dimension values, such as `52do`. */
    val DIMENSION_OCR_NOISE = Regex("(?<=\\d)[DOIl](?=\\s*(?:dp|de|do)\\b)", RegexOption.IGNORE_CASE)

    /** Detects an unlabeled dimension fragment adjacent to a labeled dimension. */
    val UNLABELED_DIMENSION = Regex("^[0-9]+\\s*(?:dp|de|do)$", RegexOption.IGNORE_CASE)

    /** Matches compact ImageView IDs like `imglogo` for drawable-backed normalization. */
    val COMPACT_IMAGE_ID = Regex("^img([a-z0-9]+)$")

    /** Matches explicit text metadata fragments without consuming the next pipe-delimited fragment. */
    val EXPLICIT_TEXT_FRAGMENT = Regex("(?:^|\\|)\\s*text\\s*[:;]\\s*([^|]+)", RegexOption.IGNORE_CASE)

    /** Matches explicit hint metadata fragments without consuming the next pipe-delimited fragment. */
    val EXPLICIT_HINT_FRAGMENT = Regex("(?:^|\\|)\\s*hint\\s*[:;]\\s*([^|]+)", RegexOption.IGNORE_CASE)

    /** Detects metadata key leakage inside text recovered from an EditText. */
    val EDIT_TEXT_METADATA_LEAKAGE = Regex("\\b(?:layout|inputtype|textpassword)\\b", RegexOption.IGNORE_CASE)

    /** Validates an unkeyed OCR fragment before considering it as a corroborated EditText ID. */
    val BARE_EDIT_TEXT_ID_CANDIDATE = Regex("[A-Za-z][A-Za-z0-9_]{2,}")

    /** Matches ImageView IDs split into OCR tokens such as `im view 1`. */
    val IMAGE_VIEW_ID_TOKENS = Regex("\\b(?:i?m)[_\\s-]+view[_\\s-]+\\d+\\b", RegexOption.IGNORE_CASE)

    /** Matches compact ImageView IDs such as `imview1`. */
    val IMAGE_VIEW_ID_COMPACT = Regex("\\b(?:i?m)view\\d+\\b", RegexOption.IGNORE_CASE)

    /** Extracts an explicit ID value from ImageView metadata. */
    val EXPLICIT_LOWER_ID = Regex("\\bid\\s*:\\s*([a-z0-9_-]+)", RegexOption.IGNORE_CASE)

    /** Detects duplicated square dimensions in compact ImageView OCR. */
    val ADJACENT_REPEATED_DIMENSION = Regex(
        "\\b(?:width|height|layout_width|layout_height)\\s*:\\s*([0-9]+)\\s*(?:dp|de|do)(?:\\s+|\\s*\\|\\s*)\\1\\s*(?:dp|de|do)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Extracts a drawable name from explicit ImageView `src` metadata. */
    val EXPLICIT_SRC = Regex("\\bsrc\\s*:\\s*(?:@drawable/)?([a-z0-9_-]+)", RegexOption.IGNORE_CASE)

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

    /** Matches a clean `id` key or OCR ID alias followed by a value. */
    fun keyedIdValue(idKeyPattern: String): Regex {
        return Regex(
            "\\s*($idKeyPattern)(?:\\s*[:;]\\s*|\\s+)([A-Za-z][A-Za-z0-9_-]*)\\s*",
            RegexOption.IGNORE_CASE
        )
    }

    /** Matches compact ID fragments like `idremember` or `idiremember`. */
    fun compactIdValue(idKeyPattern: String): Regex {
        return Regex(
            "\\s*($idKeyPattern)([A-Za-z][A-Za-z0-9_-]{2,})\\s*",
            RegexOption.IGNORE_CASE
        )
    }

    /** Removes an ID-like OCR fragment accidentally appended to text or hint OCR. */
    fun trailingFailedIdFragment(idKeyPattern: String): Regex {
        return Regex("\\s+(?:$idKeyPattern)\\s+[A-Za-z][A-Za-z0-9_-]*\\s*$", RegexOption.IGNORE_CASE)
    }

    /** Detects a reliable explicit dimension with a visible unit. */
    fun explicitDimensionWithUnit(aliases: String): Regex {
        return Regex("\\b$aliases\\s*[:;]\\s*[0-9oOIlLSBZz]+\\s*(?:dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)
    }

    /** Detects a reliable compact dimension with a visible unit. */
    fun compactDimensionWithUnit(aliases: String): Regex {
        return Regex("\\b$aliases[iIl1|]?[0-9oOIlLSBZz]+\\s*(?:dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)
    }

    /** Finds a whole gravity value without matching partial words. */
    fun wholeGravityValue(value: String): Regex {
        return Regex("(^|\\s)${Regex.escape(value)}(\\s|$)")
    }

    const val COLOR_HEX_PREFIX = "#"
    const val RESOURCE_REFERENCE_PREFIX = "@"
}
