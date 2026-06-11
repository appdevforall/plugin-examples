package org.appdevforall.codeonthego.computervision.utils.boundary

/**
 * Finds a pair of low-activity separators that preserves left metadata, canvas, and right metadata.
 */
internal object WhitespaceSeparatorBoundaryDetector {
    /** Calculates separator boundaries from smoothed low-activity gaps and region constraints. */
    fun detect(
        projection: FloatArray,
        width: Int,
        scanStart: Int,
        scanEnd: Int
    ): Pair<Int, Int>? {
        val smoothedProjection = ProjectionSmoother.smooth(
            projection,
            radius = maxOf(1, (width * BoundaryDetectionConfig.SMOOTHING_RADIUS_PERCENT).toInt())
        )
        val gaps = findLowActivityGaps(smoothedProjection, width, scanStart, scanEnd)
        if (gaps.size < 2) return null

        return selectBestSeparatorPair(smoothedProjection, gaps, scanStart, scanEnd, width)
    }

    /** Calculates contiguous gaps below the projection activity threshold. */
    private fun findLowActivityGaps(
        projection: FloatArray,
        width: Int,
        startX: Int,
        endX: Int
    ): List<GapCandidate> {
        val maxValue = projection.maxOrNull() ?: 0f
        if (maxValue <= 0f) return emptyList()

        val threshold = maxValue * BoundaryDetectionConfig.LOW_ACTIVITY_GAP_THRESHOLD
        val minGapWidth = maxOf(3, (width * BoundaryDetectionConfig.MIN_GAP_WIDTH_PERCENT).toInt())
        val gaps = mutableListOf<GapCandidate>()
        var gapStart: Int? = null
        val scanEnd = minOf(endX, projection.size)

        for (x in startX until scanEnd) {
            val isLowActivity = projection[x] <= threshold
            if (isLowActivity && gapStart == null) gapStart = x

            val openGapStart = gapStart ?: continue
            if (isLowActivity && x < scanEnd - 1) continue

            val gapEnd = if (isLowActivity) x else x - 1
            if (gapEnd - openGapStart + 1 >= minGapWidth) {
                gaps.add(GapCandidate(openGapStart, gapEnd))
            }
            gapStart = null
        }

        return gaps
    }

    /** Scores valid separator pairs by canvas projection mass and selects the strongest pair. */
    private fun selectBestSeparatorPair(
        projection: FloatArray,
        gaps: List<GapCandidate>,
        scanStart: Int,
        scanEnd: Int,
        width: Int
    ): Pair<Int, Int>? {
        val totalMass = projection.sumRange(scanStart, scanEnd - 1)
        val constraints = SeparatorConstraints(
            minRegionWidth = maxOf(1, (width * BoundaryDetectionConfig.MIN_REGION_WIDTH_PERCENT).toInt()),
            minRegionMass = totalMass * BoundaryDetectionConfig.MIN_REGION_MASS_PERCENT,
            minCanvasMass = totalMass * BoundaryDetectionConfig.MIN_CANVAS_MASS_PERCENT
        )

        return gaps.separatorPairs()
            .mapNotNull { (leftGap, rightGap) ->
                createCandidateOrNull(projection, leftGap, rightGap, scanStart, scanEnd, constraints)
            }
            .maxByOrNull { it.canvasMass }
            ?.toBoundaryPair()
    }

    /** Calculates region masses and returns a candidate only when all constraints pass. */
    private fun createCandidateOrNull(
        projection: FloatArray,
        leftGap: GapCandidate,
        rightGap: GapCandidate,
        scanStart: Int,
        scanEnd: Int,
        constraints: SeparatorConstraints
    ): SeparatorCandidate? {
        if (!hasRequiredRegionWidths(leftGap, rightGap, scanStart, scanEnd, constraints.minRegionWidth)) {
            return null
        }

        val leftMass = projection.sumRange(scanStart, leftGap.start - 1)
        val canvasMass = projection.sumRange(leftGap.end + 1, rightGap.start - 1)
        val rightMass = projection.sumRange(rightGap.end + 1, scanEnd - 1)
        return when {
            leftMass < constraints.minRegionMass -> null
            canvasMass < constraints.minCanvasMass -> null
            rightMass < constraints.minRegionMass -> null
            else -> SeparatorCandidate(leftGap, rightGap, canvasMass)
        }
    }

    /** Checks calculated left, canvas, and right region widths against the minimum width. */
    private fun hasRequiredRegionWidths(
        leftGap: GapCandidate,
        rightGap: GapCandidate,
        scanStart: Int,
        scanEnd: Int,
        minRegionWidth: Int
    ): Boolean {
        return leftGap.start - scanStart >= minRegionWidth &&
            rightGap.start - leftGap.end - 1 >= minRegionWidth &&
            scanEnd - rightGap.end - 1 >= minRegionWidth
    }

    /** Produces every ordered pair of distinct separator gaps. */
    private fun List<GapCandidate>.separatorPairs(): Sequence<Pair<GapCandidate, GapCandidate>> {
        return asSequence().flatMapIndexed { leftIndex, leftGap ->
            drop(leftIndex + 1).asSequence().map { rightGap -> leftGap to rightGap }
        }
    }

    /** Calculates the inclusive sum of projection values in a column range. */
    private fun FloatArray.sumRange(start: Int, end: Int): Float {
        var sum = 0f
        for (index in start..end) sum += this[index]
        return sum
    }
}
