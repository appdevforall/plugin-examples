package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object IdPatterns {
    const val IMAGE_VIEW_ID_PREFIX = "im"
    const val IMAGE_VIEW_ID_VIEW_TOKEN = "view"

    /** Validates an unkeyed OCR fragment before considering it as a corroborated EditText ID. */
    val BARE_EDIT_TEXT_ID_CANDIDATE = Regex("[A-Za-z][A-Za-z0-9_]{2,}")

    /** Extracts an explicit ID value from ImageView metadata. */
    val EXPLICIT_LOWER_ID = Regex("\\bid\\s*:\\s*([a-z0-9_-]+)", RegexOption.IGNORE_CASE)

    /** Matches canonical checkbox group IDs used to derive child CheckBox IDs. */
    val CHECKBOX_GROUP_ID = Regex("^cb_group_\\d+$")

    /** Matches compact checkbox group OCR variants like `cbgraup2`. */
    val COMPACT_CHECKBOX_GROUP_ID = Regex("^cbgr[oa]up?(\\d+)$")

    /** Matches RadioButton child IDs that accidentally contain a radio group ID. */
    val RADIO_BUTTON_GROUP_CHILD_ID = Regex("^rb_group(?:_\\d+)?(?:_|$).*")

    /** Matches RadioButton child IDs that accidentally contain a RadioGroup ID. */
    val RADIO_GROUP_CHILD_ID = Regex("^radio_group(?:_\\d+)?(?:_|$).*")

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
}
