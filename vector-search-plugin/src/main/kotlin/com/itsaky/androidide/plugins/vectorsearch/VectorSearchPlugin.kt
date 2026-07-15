package com.itsaky.androidide.plugins.vectorsearch

import android.util.Log
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "VectorSearchPlugin"

/**
 * Vector Search Plugin provides semantic code search capabilities.
 *
 * When activated, it indexes the project using on-device LLM embeddings
 * and stores them in a local SQLite database. Other plugins can use
 * this service to perform semantic searches.
 */
class VectorSearchPlugin : IPlugin {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var context: PluginContext
    private lateinit var indexingService: EmbeddingIndexingService

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        Log.i(TAG, "VectorSearchPlugin initialized")
        return true
    }

    override fun activate(): Boolean {
        Log.i(TAG, "VectorSearchPlugin activating...")

        // Initialize indexing service
        indexingService = EmbeddingIndexingService(context.androidContext)

        Log.i(TAG, "VectorSearchPlugin activated. Call indexProject() to start indexing.")
        return true
    }

    override fun deactivate(): Boolean {
        Log.i(TAG, "VectorSearchPlugin deactivating")
        return true
    }

    override fun dispose() {
        Log.i(TAG, "VectorSearchPlugin disposed")
    }

    /**
     * Initiates project indexing (files will need to be chunked and embeddings generated separately).
     * Should be called after activate().
     */
    fun prepareIndexing() {
        Log.i(TAG, "Project indexing prepared. Use getFiles() and storeEmbedding() to index.")
    }

    /**
     * Gets the list of code files to index in the current project.
     */
    fun getFiles(): List<java.io.File> {
        val projectDir = java.io.File(
            System.getProperty("project.dir") ?: System.getProperty("user.dir") ?: return emptyList()
        )
        return indexingService.collectFiles(projectDir)
    }

    /**
     * Searches embeddings semantically.
     * Requires the project to have been indexed first.
     *
     * @param query Search query string
     * @param backendId LLM backend to use (default "local")
     * @param topK Maximum number of results to return (default 10)
     * @return List of CodeEmbedding results ranked by relevance
     */
    suspend fun search(
        query: String,
        backendId: String = "local",
        topK: Int = 10,
    ): List<CodeEmbedding> {
        return try {
            val llmService = context.services.get(LlmInferenceService::class.java)
                ?: run { Log.e(TAG, "LlmInferenceService not available"); return emptyList() }

            // Generate embedding for the query
            val queryEmbedding = llmService.getEmbeddings(query, backendId).get()
            if (queryEmbedding.isEmpty()) {
                Log.e(TAG, "Failed to generate embedding for query")
                return emptyList()
            }

            // Get all indexed embeddings and search
            val allEmbeddings = indexingService.getAllEmbeddings()
            if (allEmbeddings.isEmpty()) {
                Log.w(TAG, "No embeddings indexed yet. Call indexProject() first.")
                return emptyList()
            }

            val results = VectorSearchService.searchWithScores(queryEmbedding, allEmbeddings, topK = topK)
            Log.d(TAG, "Search for '$query' returned ${results.size} results")

            results.map { it.first }
        } catch (e: Exception) {
            Log.e(TAG, "Error during search", e)
            emptyList()
        }
    }

    /**
     * Clears the index (e.g., for reindexing after project changes).
     */
    fun clearIndex() {
        indexingService.clearIndex()
        Log.i(TAG, "Index cleared")
    }
}
