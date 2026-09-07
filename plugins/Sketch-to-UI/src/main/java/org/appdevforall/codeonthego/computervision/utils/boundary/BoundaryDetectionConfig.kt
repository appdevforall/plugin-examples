package org.appdevforall.codeonthego.computervision.utils.boundary

internal object BoundaryDetectionConfig {
    /** Image-width fraction ignored at each outer edge. */
    const val DEFAULT_EDGE_IGNORE_PERCENT = 0.05f

    /** Image-width fraction where the left fallback search zone ends. */
    const val LEFT_ZONE_END_PERCENT = 0.5f

    /** Image-width fraction where the right fallback search zone starts. */
    const val RIGHT_ZONE_START_PERCENT = 0.5f

    /** Minimum image-width fraction required for a separator gap. */
    const val MIN_GAP_WIDTH_PERCENT = 0.02

    /** Peak-activity fraction below which columns form gaps during the primary search. */
    const val PRIMARY_ACTIVITY_THRESHOLD = 0.05f

    /** Peak-activity fraction used after normalizing a weak fallback signal. */
    const val FALLBACK_ACTIVITY_THRESHOLD = 0.01f

    /** Peak-activity fraction below which columns form whitespace separator candidates. */
    const val LOW_ACTIVITY_GAP_THRESHOLD = 0.03f

    /** Image-width fraction used as the projection smoothing radius. */
    const val SMOOTHING_RADIUS_PERCENT = 0.006f

    /** Minimum image-width fraction required for each separated region. */
    const val MIN_REGION_WIDTH_PERCENT = 0.08f

    /** Minimum total projection-mass fraction required for each metadata region. */
    const val MIN_REGION_MASS_PERCENT = 0.06f

    /** Minimum total projection-mass fraction required for the canvas region. */
    const val MIN_CANVAS_MASS_PERCENT = 0.20f

    /** Default left boundary when no suitable separator is detected. */
    private const val LEFT_FALLBACK_BOUND_PERCENT = 0.15f

    /** Default right boundary when no suitable separator is detected. */
    private const val RIGHT_FALLBACK_BOUND_PERCENT = 0.85f

    /** Calculates default left and right boundary pixels from image width. */
    fun fallbackBounds(width: Int): Pair<Int, Int> {
        return (width * LEFT_FALLBACK_BOUND_PERCENT).toInt() to
            (width * RIGHT_FALLBACK_BOUND_PERCENT).toInt()
    }
}
