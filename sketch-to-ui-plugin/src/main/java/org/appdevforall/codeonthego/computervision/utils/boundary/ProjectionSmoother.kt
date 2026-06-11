package org.appdevforall.codeonthego.computervision.utils.boundary

/**
 * Smooths projection values with a moving average to suppress narrow OCR noise.
 */
internal object ProjectionSmoother {
    /** Calculates centered moving averages using prefix sums. */
    fun smooth(projection: FloatArray, radius: Int): FloatArray {
        if (projection.isEmpty() || radius <= 0) return projection

        val prefixSums = DoubleArray(projection.size + 1)
        projection.forEachIndexed { index, value ->
            prefixSums[index + 1] = prefixSums[index] + value
        }

        return FloatArray(projection.size) { index ->
            val windowStart = maxOf(0, index - radius)
            val windowEndExclusive = minOf(projection.size, index + radius + 1)
            val windowSum = prefixSums[windowEndExclusive] - prefixSums[windowStart]
            (windowSum / (windowEndExclusive - windowStart)).toFloat()
        }
    }
}
