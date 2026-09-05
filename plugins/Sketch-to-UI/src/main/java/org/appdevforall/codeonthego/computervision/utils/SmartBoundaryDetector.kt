package org.appdevforall.codeonthego.computervision.utils

import android.graphics.Bitmap
import org.appdevforall.codeonthego.computervision.utils.BitmapUtils.calculateVerticalProjection
import org.appdevforall.codeonthego.computervision.utils.boundary.BoundaryDetectionConfig
import org.appdevforall.codeonthego.computervision.utils.boundary.GapBasedBoundaryDetector
import org.appdevforall.codeonthego.computervision.utils.boundary.WhitespaceSeparatorBoundaryDetector

/**
 * Detects sketch metadata boundaries from vertical ink projection.
 */
object SmartBoundaryDetector {
    /** Calculates smart boundaries from the bitmap's vertical ink projection. */
    fun detectSmartBoundaries(
        bitmap: Bitmap,
        edgeIgnorePercent: Float = BoundaryDetectionConfig.DEFAULT_EDGE_IGNORE_PERCENT
    ): Pair<Int, Int> {
        return detectSmartBoundariesFromProjection(
            projection = calculateVerticalProjection(bitmap),
            imageWidth = bitmap.width,
            edgeIgnorePercent = edgeIgnorePercent
        )
    }

    /** Converts width percentages to scan ranges before selecting separator or fallback bounds. */
    internal fun detectSmartBoundariesFromProjection(
        projection: FloatArray,
        imageWidth: Int,
        edgeIgnorePercent: Float = BoundaryDetectionConfig.DEFAULT_EDGE_IGNORE_PERCENT
    ): Pair<Int, Int> {
        val ignoredEdgePixels = (imageWidth * edgeIgnorePercent).toInt()
        val leftZoneEnd = (imageWidth * BoundaryDetectionConfig.LEFT_ZONE_END_PERCENT).toInt()
        val rightZoneStart = (imageWidth * BoundaryDetectionConfig.RIGHT_ZONE_START_PERCENT).toInt()
        val rightZoneEnd = imageWidth - ignoredEdgePixels

        if (ignoredEdgePixels >= leftZoneEnd || rightZoneStart >= rightZoneEnd) {
            return BoundaryDetectionConfig.fallbackBounds(imageWidth)
        }

        return WhitespaceSeparatorBoundaryDetector.detect(
            projection = projection,
            width = imageWidth,
            scanStart = ignoredEdgePixels,
            scanEnd = rightZoneEnd
        ) ?: GapBasedBoundaryDetector.detect(
            projection = projection,
            width = imageWidth,
            ignoredEdgePixels = ignoredEdgePixels,
            leftZoneEnd = leftZoneEnd,
            rightZoneStart = rightZoneStart,
            rightZoneEnd = rightZoneEnd
        )
    }
}
