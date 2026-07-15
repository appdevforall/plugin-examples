package com.itsaky.androidide.plugins.vectorsearch

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "EmbeddingIndexing"

/**
 * SQLite helper for embeddings storage.
 */
class EmbeddingsDbHelper(context: Context) : SQLiteOpenHelper(context, "embeddings.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS embeddings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                key TEXT UNIQUE,
                file_path TEXT,
                chunk_text TEXT,
                language TEXT,
                chunk_index INTEGER,
                start_line INTEGER,
                end_line INTEGER,
                embedding BLOB
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_file_path ON embeddings(file_path)")
    }

    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        // No-op for now
    }
}

/**
 * Service for indexing code files into embeddings stored in a local SQLite database.
 *
 * Walks the project tree, chunks files, generates embeddings via LlmInferenceService,
 * and stores them for later semantic search.
 */
class EmbeddingIndexingService(private val context: Context) {

    private val dbHelper = EmbeddingsDbHelper(context)

    /**
     * Stores a pre-computed embedding in the database.
     * Call this method after generating embeddings via LlmInferenceService.
     *
     * @param embedding The CodeEmbedding to store
     */
    fun storeEmbeddingDirect(embedding: CodeEmbedding) {
        storeEmbedding(embedding)
        Log.d(TAG, "Stored embedding for ${embedding.filePath}:${embedding.chunkIndex}")
    }

    /**
     * Collects and returns all code files in a directory that can be chunked and indexed.
     * Caller is responsible for generating embeddings and storing them via storeEmbeddingDirect().
     *
     * @param projectRoot Root directory of the project
     * @param maxFiles Maximum number of files to collect (default 500)
     * @return List of code files ready for chunking
     */
    fun collectFiles(projectRoot: File, maxFiles: Int = 500): List<File> {
        return collectCodeFiles(projectRoot, maxFiles)
    }

    fun languageFor(file: File): String {
        return getLanguageFromExtension(file.extension)
    }

    /**
     * Retrieves all embeddings from the database.
     */
    fun getAllEmbeddings(): List<CodeEmbedding> {
        val db = dbHelper.readableDatabase
        val embeddings = mutableListOf<CodeEmbedding>()

        db.query(
            "embeddings",
            arrayOf("key", "file_path", "chunk_text", "language", "chunk_index", "start_line", "end_line", "embedding"),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val buffer = cursor.getBlob(7)
                val embedding = FloatArray(buffer.size / 4)
                val byteBuffer = ByteBuffer.wrap(buffer)
                for (i in embedding.indices) {
                    embedding[i] = byteBuffer.float
                }

                embeddings.add(
                    CodeEmbedding(
                        key = cursor.getString(0),
                        filePath = cursor.getString(1),
                        chunkText = cursor.getString(2),
                        language = cursor.getString(3),
                        chunkIndex = cursor.getInt(4),
                        startLine = cursor.getInt(5),
                        endLine = cursor.getInt(6),
                        embedding = embedding,
                    )
                )
            }
        }

        return embeddings
    }

    /**
     * Clears all embeddings from the database (useful for reindexing).
     */
    fun clearIndex() {
        val db = dbHelper.writableDatabase
        db.delete("embeddings", null, null)
        Log.i(TAG, "Index cleared")
    }

    private fun storeEmbedding(embedding: CodeEmbedding) {
        val db = dbHelper.writableDatabase
        val buffer = ByteBuffer.allocate(embedding.embedding.size * 4)
        for (f in embedding.embedding) {
            buffer.putFloat(f)
        }

        val values = ContentValues().apply {
            put("key", embedding.key)
            put("file_path", embedding.filePath)
            put("chunk_text", embedding.chunkText)
            put("language", embedding.language)
            put("chunk_index", embedding.chunkIndex)
            put("start_line", embedding.startLine)
            put("end_line", embedding.endLine)
            put("embedding", buffer.array())
        }

        db.insertWithOnConflict("embeddings", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun collectCodeFiles(root: File, maxCount: Int): List<File> {
        val files = mutableListOf<File>()
        val skipDirs = setOf("build", ".gradle", "node_modules", ".git", "dist", "out")

        fun walk(dir: File) {
            if (files.size >= maxCount) return
            if (dir.name.startsWith(".") && dir.name != ".") return
            if (dir.name in skipDirs) return

            try {
                dir.listFiles()?.forEach { file ->
                    when {
                        file.isDirectory -> walk(file)
                        file.isFile && isCodeFile(file) -> files.add(file)
                    }
                    if (files.size >= maxCount) return@forEach
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error walking directory ${dir.absolutePath}", e)
            }
        }

        walk(root)
        return files
    }

    private fun isCodeFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in setOf("kt", "java", "xml", "gradle", "kts", "py", "js", "ts")
    }

    private fun getLanguageFromExtension(ext: String): String {
        return when (ext.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "xml" -> "xml"
            "gradle" -> "gradle"
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            else -> "text"
        }
    }
}
