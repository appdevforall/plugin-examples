package org.appdevforall.codeonthego.computervision.utils

import android.graphics.Bitmap
import org.appdevforall.codeonthego.computervision.utils.BitmapUtils.calculateVerticalProjection

/**
 * Detects sketch metadata boundaries from vertical ink projection.
 *
 * The primary path looks for two low-activity separators that preserve left metadata, canvas, and
 * right metadata regions. If that three-region structure is not present, it falls back to the older
 * single-gap search.
 */
object SmartBoundaryDetector {

    private const val DEFAULT_EDGE_IGNORE_PERCENT = 0.05f
    private const val LEFT_ZONE_END_PERCENT = 0.5f
    private const val RIGHT_ZONE_START_PERCENT = 0.5f
    private const val MIN_GAP_WIDTH_PERCENT = 0.02
    private const val PRIMARY_ACTIVITY_THRESHOLD = 0.05f
    private const val FALLBACK_ACTIVITY_THRESHOLD = 0.01f
    private const val LEFT_FALLBACK_BOUND_PERCENT = 0.15f
    private const val RIGHT_FALLBACK_BOUND_PERCENT = 0.85f

    // Columns at or below this fraction of the peak projection can form separator gaps.
    private const val LOW_ACTIVITY_GAP_THRESHOLD = 0.03f

    // Projection smoothing window, expressed as a fraction of image width.
    private const val SMOOTHING_RADIUS_PERCENT = 0.006f

    // Minimum region size required for left metadata, canvas, and right metadata areas.
    private const val MIN_REGION_WIDTH_PERCENT = 0.08f
    private const val MIN_REGION_MASS_PERCENT = 0.06f

    // Canvas region must carry this share of detected mass to reject internal canvas gaps.
    private const val MIN_CANVAS_MASS_PERCENT = 0.20f

    fun detectSmartBoundaries(
        bitmap: Bitmap,
        edgeIgnorePercent: Float = DEFAULT_EDGE_IGNORE_PERCENT
    ): Pair<Int, Int> {
        val projection = calculateVerticalProjection(bitmap)
        return detectSmartBoundariesFromProjection(
            projection = projection,
            imageWidth = bitmap.width,
            edgeIgnorePercent = edgeIgnorePercent
        )
    }

    internal fun detectSmartBoundariesFromProjection(
        projection: FloatArray,
        imageWidth: Int,
        edgeIgnorePercent: Float = DEFAULT_EDGE_IGNORE_PERCENT
    ): Pair<Int, Int> {
        val width = imageWidth
        val minimumGapWidth = (width * MIN_GAP_WIDTH_PERCENT).toInt()

        val ignoredEdgePixels = (width * edgeIgnorePercent).toInt()
        val leftZoneEnd = (width * LEFT_ZONE_END_PERCENT).toInt()
        val rightZoneStart = (width * RIGHT_ZONE_START_PERCENT).toInt()
        val rightZoneEnd = width - ignoredEdgePixels

        if (ignoredEdgePixels >= leftZoneEnd || rightZoneStart >= rightZoneEnd) {
            return Pair(
                (width * LEFT_FALLBACK_BOUND_PERCENT).toInt(),
                (width * RIGHT_FALLBACK_BOUND_PERCENT).toInt()
            )
        }

        detectWhitespaceSeparatorBoundaries(projection, width, ignoredEdgePixels, rightZoneEnd)?.let { separatorBounds ->
            return separatorBounds
        }

        return detectGapBasedBoundaries(projection, width, ignoredEdgePixels, leftZoneEnd, rightZoneStart, rightZoneEnd, minimumGapWidth)
    }

    private fun detectGapBasedBoundaries(
        projection: FloatArray,
        width: Int,
        ignoredEdgePixels: Int,
        leftZoneEnd: Int,
        rightZoneStart: Int,
        rightZoneEnd: Int,
        minimumGapWidth: Int
    ): Pair<Int, Int> {
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

        val finalLeftBound = leftBound ?: (width * LEFT_FALLBACK_BOUND_PERCENT).toInt()
        val finalRightBound = rightBound ?: (width * RIGHT_FALLBACK_BOUND_PERCENT).toInt()
        return Pair(finalLeftBound, finalRightBound)
    }

    private fun detectWhitespaceSeparatorBoundaries(
        projection: FloatArray,
        width: Int,
        ignoredEdgePixels: Int,
        rightZoneEnd: Int
    ): Pair<Int, Int>? {
        val smoothedProjection = smoothProjection(projection, radius = maxOf(1, (width * SMOOTHING_RADIUS_PERCENT).toInt()))
        val gapCandidates = findLowActivityGaps(
            projection = smoothedProjection,
            width = width,
            startX = ignoredEdgePixels,
            endX = rightZoneEnd
        )
        if (gapCandidates.size < 2) {
            return null
        }

        return selectBestSeparatorPair(
            projection = smoothedProjection,
            gaps = gapCandidates,
            scanStart = ignoredEdgePixels,
            scanEnd = rightZoneEnd,
            width = width
        )
    }

    private fun smoothProjection(projection: FloatArray, radius: Int): FloatArray {
        if (projection.isEmpty() || radius <= 0) return projection

        val smoothed = FloatArray(projection.size)
        var sum = 0f
        var windowStart = 0
        var windowEnd = -1

        for (index in projection.indices) {
            val targetStart = maxOf(0, index - radius)
            val targetEnd = minOf(projection.lastIndex, index + radius)

            while (windowEnd < targetEnd) {
                windowEnd++
                sum += projection[windowEnd]
            }
            while (windowStart < targetStart) {
                sum -= projection[windowStart]
                windowStart++
            }

            smoothed[index] = sum / (windowEnd - windowStart + 1)
        }
        return smoothed
    }

    private fun findLowActivityGaps(
        projection: FloatArray,
        width: Int,
        startX: Int,
        endX: Int
    ): List<GapCandidate> {
        val maxValue = projection.maxOrNull() ?: 0f
        if (maxValue <= 0f) return emptyList()

        val threshold = maxValue * LOW_ACTIVITY_GAP_THRESHOLD
        val minGapWidth = maxOf(3, (width * MIN_GAP_WIDTH_PERCENT).toInt())
        val gaps = mutableListOf<GapCandidate>()
        var gapStart: Int? = null
        val scanEnd = minOf(endX, projection.size)

        for (x in startX until scanEnd) {
            val isLowActivity = projection[x] <= threshold
            if (isLowActivity && gapStart == null) {
                gapStart = x
            }

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

    /**
     * Chooses the low-activity gap pair that best separates the expected three sketch regions.
     *
     * Candidate pairs must leave enough width and projection mass in both metadata regions and the
     * central canvas, which prevents internal canvas whitespace from being selected as a boundary.
     */
    private fun selectBestSeparatorPair(
        projection: FloatArray,
        gaps: List<GapCandidate>,
        scanStart: Int,
        scanEnd: Int,
        width: Int
    ): Pair<Int, Int>? {
        val totalMass = projection.sumRange(scanStart, scanEnd - 1)
        val constraints = SeparatorConstraints(
            minRegionWidth = maxOf(1, (width * MIN_REGION_WIDTH_PERCENT).toInt()),
            minRegionMass = totalMass * MIN_REGION_MASS_PERCENT,
            minCanvasMass = totalMass * MIN_CANVAS_MASS_PERCENT
        )

        return gaps.separatorPairs()
            .mapNotNull { (leftGap, rightGap) ->
                createSeparatorCandidateOrNull(
                    projection = projection,
                    leftGap = leftGap,
                    rightGap = rightGap,
                    scanStart = scanStart,
                    scanEnd = scanEnd,
                    constraints = constraints
                )
            }
            .maxByOrNull { it.canvasMass }
            ?.toBoundaryPair()
    }

    private fun List<GapCandidate>.separatorPairs(): Sequence<Pair<GapCandidate, GapCandidate>> {
        return asSequence().flatMapIndexed { leftIndex, leftGap ->
            drop(leftIndex + 1).asSequence().map { rightGap -> leftGap to rightGap }
        }
    }

    private fun createSeparatorCandidateOrNull(
        projection: FloatArray,
        leftGap: GapCandidate,
        rightGap: GapCandidate,
        scanStart: Int,
        scanEnd: Int,
        constraints: SeparatorConstraints
    ): SeparatorCandidate? {
        return when {
            !hasRequiredRegionWidths(leftGap, rightGap, scanStart, scanEnd, constraints.minRegionWidth) -> null
            else -> createCandidateWithValidMasses(projection, leftGap, rightGap, scanStart, scanEnd, constraints)
        }
    }

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

    private fun createCandidateWithValidMasses(
        projection: FloatArray,
        leftGap: GapCandidate,
        rightGap: GapCandidate,
        scanStart: Int,
        scanEnd: Int,
        constraints: SeparatorConstraints
    ): SeparatorCandidate? {
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

    private fun FloatArray.sumRange(start: Int, end: Int): Float {
        var sum = 0f
        for (index in start..end) {
            sum += this[index]
        }
        return sum
    }

    private fun findBestGapMidpoint(
        signalSegment: FloatArray,
        offset: Int = 0,
        normalizeSignal: Boolean = false
    ): Pair<Int?, Int> {
        if (signalSegment.isEmpty()) {
            return Pair(null, 0)
        }

        val signal = if (normalizeSignal) {
            val minValue = signalSegment.minOrNull() ?: 0f
            FloatArray(signalSegment.size) { index -> signalSegment[index] - minValue }
        } else {
            signalSegment
        }

        val activityThresholdMultiplier = if (normalizeSignal) {
            FALLBACK_ACTIVITY_THRESHOLD
        } else {
            PRIMARY_ACTIVITY_THRESHOLD
        }
        val threshold = (signal.maxOrNull() ?: 0f) * activityThresholdMultiplier

        var maxGapLength = 0
        var maxGapMidpoint: Int? = null
        var currentGapStart = -1
        var previousIsActive = true

        signal.forEachIndexed { index, value ->
            val isActive = value > threshold
            if (previousIsActive && !isActive) {
                currentGapStart = index
            }

            val isGapClosing = currentGapStart != -1 && (index + 1 == signal.size || (!isActive && signal[index + 1] > threshold))
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

        return Pair(maxGapMidpoint?.plus(offset), maxGapLength)
    }

    private data class GapCandidate(
        val start: Int,
        val end: Int
    ) {
        val width: Int
            get() = end - start + 1

        val midpoint: Int
            get() = start + (width / 2)
    }

    private data class SeparatorConstraints(
        val minRegionWidth: Int,
        val minRegionMass: Float,
        val minCanvasMass: Float
    )

    private data class SeparatorCandidate(
        val leftGap: GapCandidate,
        val rightGap: GapCandidate,
        val canvasMass: Float
    ) {
        fun toBoundaryPair(): Pair<Int, Int> {
            return leftGap.midpoint to rightGap.midpoint
        }
    }
}
