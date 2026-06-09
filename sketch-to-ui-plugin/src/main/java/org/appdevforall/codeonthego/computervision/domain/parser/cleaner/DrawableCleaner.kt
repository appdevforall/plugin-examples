package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner

internal object DrawableCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("@drawable/")) return rawValue

        val cleaned = rawValue.lowercase()
            .replace(AttributeRegexPatterns.DRAWABLE_EXTENSION_SUFFIX, "")
            .replace(AttributeRegexPatterns.OCR_IM_OR_M_CONFUSION) { match ->
                if (match.value == "inm") "im" else "m"
            }
            .replace(AttributeRegexPatterns.RESOURCE_NAME_UNSAFE_CHARS, "_")
            .replace(AttributeRegexPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')
        val finalCleaned = cleaned
            .replace("im_age", "image")
            .replace(AttributeRegexPatterns.STANDALONE_IM_TOKEN, "$1image$2")
            .replace(AttributeRegexPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')

        return if (finalCleaned.isEmpty()) rawValue else "@drawable/$finalCleaned"
    }
}
