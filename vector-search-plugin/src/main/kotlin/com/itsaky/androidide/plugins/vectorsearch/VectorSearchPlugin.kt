package com.itsaky.androidide.plugins.vectorsearch

import android.util.Log
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.ProjectSearchExtension
import com.itsaky.androidide.plugins.extensions.ProjectSearchRequest
import com.itsaky.androidide.plugins.extensions.ProjectSearchResult
import com.itsaky.androidide.plugins.extensions.ProjectSearchSection
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.math.sqrt

private const val TAG = "VectorSearchPlugin"
private const val AI_CORE_PLUGIN_ID = "com.itsaky.androidide.plugins.aicore"
private const val SEMANTIC_RESULTS_TITLE = "Semantic Results"
private const val FALLBACK_EMBEDDING_DIMENSIONS = 384

/**
 * Vector Search Plugin provides semantic code search capabilities.
 *
 * When activated, it indexes the project using on-device LLM embeddings
 * and stores them in a local SQLite database. Other plugins can use
 * this service to perform semantic searches.
 */
class VectorSearchPlugin : IPlugin, ProjectSearchExtension {

    private lateinit var context: PluginContext
    private lateinit var indexingService: EmbeddingIndexingService
    @Volatile private var indexedRootsKey: String? = null

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

    override fun searchProject(request: ProjectSearchRequest): CompletableFuture<List<ProjectSearchSection>> {
        Log.i(TAG, "Project search requested for '${request.query}'")
        return CompletableFuture.supplyAsync {
            val results = runBlocking {
                search(
                    query = request.query,
                    backendId = "local",
                    roots = request.roots,
                    topK = 10,
                )
            }
            if (results.isEmpty()) {
                Log.i(TAG, "No semantic results available for '${request.query}'")
                emptyList()
            } else {
                listOf(
                    ProjectSearchSection(
                        title = SEMANTIC_RESULTS_TITLE,
                        results = results.map { it.toProjectSearchResult(request.query) },
                    )
                )
            }
        }
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
        roots: List<File> = emptyList(),
        topK: Int = 10,
    ): List<CodeEmbedding> {
        return try {
            val llmService = SharedServices.get(LlmInferenceService::class.java)
                ?: context.getPluginService(AI_CORE_PLUGIN_ID, LlmInferenceService::class.java)
                ?: context.services.get(LlmInferenceService::class.java)

            if (roots.isNotEmpty()) {
                ensureIndexed(roots, llmService, backendId)
            }

            val queryEmbedding = generateEmbedding(query, llmService, backendId)
            val allEmbeddings = indexingService.getAllEmbeddings()
            if (allEmbeddings.isEmpty()) {
                Log.w(TAG, "No embeddings indexed yet")
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
        indexedRootsKey = null
        Log.i(TAG, "Index cleared")
    }

    private fun ensureIndexed(
        roots: List<File>,
        llmService: LlmInferenceService?,
        backendId: String,
    ) {
        val rootsKey = roots
            .map { it.absolutePath }
            .sorted()
            .joinToString("|")
        if (indexedRootsKey == rootsKey && indexingService.getAllEmbeddings().isNotEmpty()) {
            return
        }

        indexingService.clearIndex()
        var fileCount = 0
        var chunkCount = 0

        roots.forEach { root ->
            indexingService.collectFiles(root).forEach { file ->
                fileCount++
                val language = indexingService.languageFor(file)
                val chunks = try {
                    CodeChunker.chunkFile(file)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to chunk ${file.absolutePath}", e)
                    emptyList()
                }

                chunks.forEachIndexed { index, chunk ->
                    val embedding = CodeEmbedding(
                        key = "${file.absolutePath}:$index",
                        filePath = file.absolutePath,
                        chunkText = chunk.content,
                        language = language,
                        chunkIndex = index,
                        startLine = chunk.startLine + 1,
                        endLine = chunk.endLine + 1,
                        embedding = generateEmbedding(chunk.content, llmService, backendId),
                    )
                    indexingService.storeEmbeddingDirect(embedding)
                    chunkCount++
                }
            }
        }

        indexedRootsKey = rootsKey
        Log.i(TAG, "Indexed $chunkCount chunks from $fileCount files")
    }

    private fun generateEmbedding(
        text: String,
        llmService: LlmInferenceService?,
        backendId: String,
    ): FloatArray {
        val llmEmbedding = try {
            llmService?.getEmbeddings(text, backendId)?.get()
        } catch (e: Exception) {
            Log.w(TAG, "LLM embeddings unavailable, using lexical fallback", e)
            null
        }
        return if (llmEmbedding != null && llmEmbedding.isNotEmpty()) {
            llmEmbedding
        } else {
            lexicalEmbedding(text)
        }
    }

    private fun lexicalEmbedding(text: String): FloatArray {
        val vector = FloatArray(FALLBACK_EMBEDDING_DIMENSIONS)
        lexicalTokens(text).forEach { token ->
            val slot = (token.hashCode() and Int.MAX_VALUE) % vector.size
            vector[slot] += 1f
        }

        var normSquared = 0f
        for (value in vector) {
            normSquared += value * value
        }
        if (normSquared == 0f) {
            return vector
        }

        val norm = sqrt(normSquared)
        for (i in vector.indices) {
            vector[i] = vector[i] / norm
        }
        return vector
    }

    private fun lexicalTokens(text: String): Sequence<String> {
        val words = Regex("[A-Za-z_][A-Za-z0-9_]*|\\d+")
            .findAll(text)
            .map { it.value.lowercase() }
        return words.flatMap { word ->
            sequence {
                yield(word)
                val maxPrefix = word.length.coerceAtMost(8)
                for (length in 3..maxPrefix) {
                    yield(word.substring(0, length))
                }
            }
        }
    }

    private fun CodeEmbedding.toProjectSearchResult(query: String): ProjectSearchResult {
        val line = startLine.coerceAtLeast(1) - 1
        return ProjectSearchResult(
            file = java.io.File(filePath),
            linePreview = chunkText.replace(Regex("\\s+"), " ").take(160),
            matchText = query,
            startLine = line,
            startColumn = 0,
            endLine = endLine.coerceAtLeast(startLine).coerceAtLeast(1) - 1,
            endColumn = 0,
        )
    }
}
