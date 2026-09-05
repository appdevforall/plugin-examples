package org.appdevforall.codeonthego.computervision.utils.boundary

internal data class GapCandidate(
    val start: Int,
    val end: Int
) {
    /** Calculates the inclusive gap width. */
    val width: Int
        get() = end - start + 1

    /** Calculates the center column of the gap. */
    val midpoint: Int
        get() = start + (width / 2)
}

internal data class SeparatorConstraints(
    val minRegionWidth: Int,
    val minRegionMass: Float,
    val minCanvasMass: Float
)

internal data class SeparatorCandidate(
    val leftGap: GapCandidate,
    val rightGap: GapCandidate,
    val canvasMass: Float
) {
    /** Converts the selected separator gaps into their midpoint boundaries. */
    fun toBoundaryPair(): Pair<Int, Int> = leftGap.midpoint to rightGap.midpoint
}
