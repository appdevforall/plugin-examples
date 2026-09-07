package org.appdevforall.codeonthego.computervision.domain.parser.patterns

internal object SpacingPatterns {
    /** Matches layout margin side keys written with a space. */
    const val LAYOUT_MARGIN_SIDE = "layout_margin\\s+(top|bottom|start|end|left|right)"
    /** Matches padding side keys written with a space. */
    const val PADDING_SIDE = "padding\\s+(top|bottom|start|end|left|right)"
}
