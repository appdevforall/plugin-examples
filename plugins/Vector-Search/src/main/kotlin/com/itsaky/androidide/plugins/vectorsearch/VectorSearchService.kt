package com.itsaky.androidide.plugins.vectorsearch

/**
 * Service for searching code embeddings by semantic similarity.
 * Given a query string's embedding, finds the most relevant stored embeddings.
 */
object VectorSearchService {

    /**
     * Searches embeddings by cosine similarity to a query embedding.
     *
     * @param queryEmbedding The embedding vector for the search query
     * @param allEmbeddings List of all stored embeddings to search
     * @param similarityThreshold Minimum similarity score (0.0-1.0) to include in results (default 0.05)
     * @param topK Maximum number of results to return (default 10)
     * @return List of (embedding, score) pairs sorted by similarity (highest first)
     */
    fun searchWithScores(
        queryEmbedding: FloatArray,
        allEmbeddings: List<CodeEmbedding>,
        similarityThreshold: Float = 0.05f,
        topK: Int = 10,
    ): List<Pair<CodeEmbedding, Float>> {
        if (allEmbeddings.isEmpty() || queryEmbedding.isEmpty()) {
            return emptyList()
        }

        // Compute cosine similarity for each embedding
        val similarities = allEmbeddings.map { embedding ->
            Pair(embedding, VectorMath.cosineSimilarity(queryEmbedding, embedding.embedding))
        }

        // Filter by threshold
        val filtered = similarities.filter { it.second >= similarityThreshold }

        // Apply relevance cutoff: keep results within 50% of top score and above 0.1 floor
        if (filtered.isEmpty()) {
            return emptyList()
        }

        val topScore = filtered.maxByOrNull { it.second }?.second ?: return emptyList()
        val relevanceThreshold = (topScore * 0.5f).coerceAtLeast(0.1f)

        return filtered
            .filter { it.second >= relevanceThreshold }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
