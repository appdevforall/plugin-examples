package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.AttributeKeyPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.CoreParserPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.DrawablePatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.ResourceNamePatterns

internal object DrawableCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("@drawable/")) return rawValue

        val cleaned = rawValue.lowercase()
            .replace(DrawablePatterns.DRAWABLE_EXTENSION_SUFFIX, "")
            .replace(AttributeKeyPatterns.OCR_IM_OR_M_CONFUSION) { match ->
                if (match.value == "inm") "im" else "m"
            }
            .replace(ResourceNamePatterns.RESOURCE_NAME_UNSAFE_CHARS, "_")
            .replace(CoreParserPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')
        val finalCleaned = cleaned
            .replace("im_age", "image")
            .replace(DrawablePatterns.STANDALONE_IM_TOKEN, "$1image$2")
            .replace(CoreParserPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')

        return if (finalCleaned.isEmpty()) rawValue else "@drawable/$finalCleaned"
    }
}
