package org.appdevforall.codeonthego.computervision.utils.boundary

/**
 * Finds the widest low-activity gap independently in each half of the image.
 */
internal object GapBasedBoundaryDetector {
    /** Calculates fallback boundaries from the widest low-activity gap in each image half. */
    fun detect(
        projection: FloatArray,
        width: Int,
        ignoredEdgePixels: Int,
        leftZoneEnd: Int,
        rightZoneStart: Int,
        rightZoneEnd: Int
    ): Pair<Int, Int> {
        val minimumGapWidth = (width * BoundaryDetectionConfig.MIN_GAP_WIDTH_PERCENT).toInt()
        val leftSignal = projection.copyOfRange(ignoredEdgePixels, leftZoneEnd)
        var (leftBound, leftGapLength) = findBestGapMidpoint(leftSignal, offset = ignoredEdgePixels)
        if (leftBound == null || leftGapLength < minimumGapWidth) {
            leftBound = findBestGapMidpoint(leftSignal, offset = ignoredEdgePixels, normalizeSignal = true).first
        }

        val rightSignal = projection.copyOfRange(rightZoneStart, rightZoneEnd)
        var (rightBound, rightGapLength) = findBestGapMidpoint(rightSignal, offset = rightZoneStart)
        if (rightBound == null || rightGapLength < minimumGapWidth) {
            rightBound = findBestGapMidpoint(rightSignal, offset = rightZoneStart, normalizeSignal = true).first
        }

        val fallbackBounds = BoundaryDetectionConfig.fallbackBounds(width)
        return (leftBound ?: fallbackBounds.first) to (rightBound ?: fallbackBounds.second)
    }

    /** Calculates the midpoint and width of the longest gap below the activity threshold. */
    private fun findBestGapMidpoint(
        signalSegment: FloatArray,
        offset: Int = 0,
        normalizeSignal: Boolean = false
    ): Pair<Int?, Int> {
        if (signalSegment.isEmpty()) return null to 0

        val signal = if (normalizeSignal) {
            val minValue = signalSegment.minOrNull() ?: 0f
            FloatArray(signalSegment.size) { index -> signalSegment[index] - minValue }
        } else {
            signalSegment
        }
        val thresholdMultiplier = if (normalizeSignal) {
            BoundaryDetectionConfig.FALLBACK_ACTIVITY_THRESHOLD
        } else {
            BoundaryDetectionConfig.PRIMARY_ACTIVITY_THRESHOLD
        }
        val threshold = (signal.maxOrNull() ?: 0f) * thresholdMultiplier

        var maxGapLength = 0
        var maxGapMidpoint: Int? = null
        var currentGapStart = -1
        var previousIsActive = true

        signal.forEachIndexed { index, value ->
            val isActive = value > threshold
            if (previousIsActive && !isActive) currentGapStart = index

            val isGapClosing = currentGapStart != -1 &&
                (index + 1 == signal.size || (!isActive && signal[index + 1] > threshold))
            if (isGapClosing) {
                val gapLength = index - currentGapStart + 1
                if (gapLength > maxGapLength) {
                    maxGapLength = gapLength
                    maxGapMidpoint = currentGapStart + (gapLength / 2)
                }
                currentGapStart = -1
            }

            previousIsActive = isActive
        }

        return maxGapMidpoint?.plus(offset) to maxGapLength
    }
}
