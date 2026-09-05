package org.appdevforall.codeonthego.computervision.domain.parser

internal object DimensionUnits {
    const val DP = "dp"
    const val SP = "sp"
    const val PX = "px"
    const val IN = "in"
    const val MM = "mm"
    const val PT = "pt"

    const val OCR_SP = "5p"
    const val OCR_DUPLICATED_DP = "ddp"
    const val OCR_DP_WITH_ZERO = "d0"
    const val OCR_DP_WITH_O = "do"
    const val OCR_DP_WITH_E = "de"

    val supportedUnits = listOf(
        DP,
        SP,
        PX,
        IN,
        MM,
        PT
    )

    val noisySuffixes = listOf(
        OCR_DUPLICATED_DP,
        DP,
        OCR_DP_WITH_ZERO,
        OCR_DP_WITH_O,
        OCR_DP_WITH_E,
        SP,
        OCR_SP,
        PX,
        IN,
        MM,
        PT
    )
}
