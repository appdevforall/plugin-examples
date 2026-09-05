package com.itsaky.androidide.plugins.vectorsearch

import kotlin.math.sqrt

/**
 * Utility object for vector mathematics operations.
 * Provides functions for comparing and analyzing embeddings.
 */
object VectorMath {

    /**
     * Calculates the cosine similarity between two vectors.
     *
     * Cosine similarity is the standard metric for comparing embeddings.
     * It measures the cosine of the angle between two vectors in a multi-dimensional space.
     *
     * Algorithm:
     * 1. Calculate dot product: sum of a[i] * b[i]
     * 2. Calculate norm for vector a: sqrt(sum of a[i]²)
     * 3. Calculate norm for vector b: sqrt(sum of b[i]²)
     * 4. Return dotProduct / (normA * normB)
     * 5. Handle zero-division: return 0.0f if denominator is 0
     *
     * @param a First embedding vector
     * @param b Second embedding vector
     * @return Float between -1.0 and 1.0 (or 0.0 if either vector has zero magnitude)
     *         - 1.0 indicates identical direction (maximum similarity)
     *         - 0.0 indicates orthogonal vectors (no similarity)
     *         - -1.0 indicates opposite direction (inverse similarity)
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        // Different-dimension vectors are incomparable; guard so a shorter b can't throw AIOOBE.
        if (a.size != b.size) {
            return 0.0f
        }

        var dotProduct = 0.0f
        var normASquared = 0.0f
        var normBSquared = 0.0f

        // Single-pass calculation for efficiency
        for (i in a.indices) {
            val aVal = a[i]
            val bVal = b[i]

            dotProduct += aVal * bVal
            normASquared += aVal * aVal
            normBSquared += bVal * bVal
        }

        val normA = sqrt(normASquared)
        val normB = sqrt(normBSquared)
        val denominator = normA * normB

        // Handle zero-division case
        return if (denominator == 0.0f) {
            0.0f
        } else {
            dotProduct / denominator
        }
    }
}
