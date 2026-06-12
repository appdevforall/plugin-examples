package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object DrawablePatterns {
    /** Removes common drawable file extensions before resource-name cleanup. */
    val DRAWABLE_EXTENSION_SUFFIX = Regex("\\.(png|jpg|jpeg|webp|xml|svg)$")

    /** Replaces a standalone OCR `im` token with `image` in drawable names. */
    val STANDALONE_IM_TOKEN = Regex("(^|_)im($|_)")

    /** Extracts a drawable name from explicit ImageView `src` metadata. */
    val EXPLICIT_SRC = Regex("\\bsrc\\s*:\\s*(?:@drawable/)?([a-z0-9_-]+)", RegexOption.IGNORE_CASE)
}
