package com.itsaky.androidide.plugins.vectorsearch

/**
 * Represents a code embedding (semantic vector) stored in the database.
 *
 * Each CodeEmbedding corresponds to a chunk of source code from a file.
 * The embedding is a 384-dimensional vector computed from the code text,
 * enabling semantic search and similarity comparisons.
 *
 * @property key Unique identifier in format "{filePath}:{chunkIndex}"
 * @property filePath Full path to the source file
 * @property chunkText The actual code text for this chunk
 * @property language Programming language (kotlin, java, or xml)
 * @property chunkIndex Which chunk of the file this is (0-indexed)
 * @property startLine Line number where chunk starts (1-indexed)
 * @property endLine Line number where chunk ends (inclusive, 1-indexed)
 * @property embedding The 384-dimensional embedding vector
 */
data class CodeEmbedding(
    val key: String,
    val filePath: String,
    val chunkText: String,
    val language: String,
    val chunkIndex: Int,
    val startLine: Int,
    val endLine: Int,
    val embedding: FloatArray,
) {

    /**
     * Custom equality check that includes floating-point array comparison.
     * Uses contentEquals for FloatArray instead of identity comparison.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CodeEmbedding

        if (key != other.key) return false
        if (filePath != other.filePath) return false
        if (chunkText != other.chunkText) return false
        if (language != other.language) return false
        if (chunkIndex != other.chunkIndex) return false
        if (startLine != other.startLine) return false
        if (endLine != other.endLine) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    /**
     * Custom hash code that includes the embedding array.
     */
    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + startLine
        result = 31 * result + endLine
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
