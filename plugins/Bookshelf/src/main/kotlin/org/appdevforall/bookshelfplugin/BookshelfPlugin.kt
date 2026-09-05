package org.appdevforall.bookshelfplugin

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

class BookshelfPlugin : IPlugin, DocumentationExtension {

    // Return null so the IDE doesn't auto-insert assets and conflict with our manual DB handling
    override fun getTier3DocsAssetPath(): String? = null

    override fun getTooltipCategory(): String = "plugin_bookshelf"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return emptyList()
    }

    override fun onDocumentationInstall(): Boolean {
        Log.i(TAG, "Installing documentation: Replacing placeholders with real books...")
        val dbFile = context.androidContext.getDatabasePath("documentation.db")
        if (!dbFile.exists()) {
            Log.e(TAG, "documentation.db not found!")
            return false
        }

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.beginTransaction()

            // ID 14 is pre-established in the schema for application/pdf with brotli compression
            val brotliPdfTypeId = 14L

            // List all files in the "books" directory from the plugin's assets
            val bookFiles = context.androidContext.assets.list("books") ?: emptyArray()

            for (fileName in bookFiles) {
                // Expect brotli-compressed files ending in .pdf.br
                if (!fileName.endsWith(".pdf.br")) {
                    Log.w(TAG, "Skipping non-brotli asset 'books/$fileName'")
                    continue
                }

                // Read the pre-compressed bytes directly
                val compressedBytes = context.resources.openPluginAsset("books/$fileName")?.use { it.readBytes() }
                if (compressedBytes == null) {
                    Log.w(TAG, "Could not read asset 'books/$fileName'")
                    continue
                }

                // Strip the .br extension to map back to standard .pdf database path
                val dbFileName = fileName.dropLast(3)

                // The target path in the database
                // NOTE: We are temporarily bypassing the "plugin/org.appdevforall.bookshelfplugin/*"
                // namespace because of how the PluginHandler removes documentation on uninstallation.
                val dbPath = "bookshelf/org.appdevforall.bookshelfplugin/$dbFileName"

                // Delete any existing book and all its fragments before inserting
                db.delete("Content", "path = ? OR path LIKE ?", arrayOf(dbPath, "$dbPath-%"))

                // Apply chunked insertion logic exactly matching PluginDocumentationManager
                insertContentChunked(db, dbPath, compressedBytes, brotliPdfTypeId)

                Log.d(TAG, "Successfully installed real book for '$dbPath'")
            }

            db.setTransactionSuccessful()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error installing real books", e)
            return false
        } finally {
            db?.endTransaction()
            db?.close()
        }
    }

    override fun onDocumentationUninstall() {
        Log.i(TAG, "Uninstalling documentation: Replacing real books with placeholders...")
        val dbFile = context.androidContext.getDatabasePath("documentation.db")
        if (!dbFile.exists()) return

        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            db.beginTransaction()

            // Find the base paths of all files installed by this plugin
            val basePaths = mutableListOf<String>()
            db.rawQuery("SELECT path FROM Content WHERE path LIKE 'bookshelf/org.appdevforall.bookshelfplugin/%.pdf'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    basePaths.add(cursor.getString(0))
                }
            }

            var totalReplaced = 0
            for (dbPath in basePaths) {
                // Delete the existing book and all its fragments
                db.delete("Content", "path = ? OR path LIKE ?", arrayOf(dbPath, "$dbPath-%"))

                // Directly copy the placeholder and its fragments using SQL INSERT/SELECT
                db.execSQL("""
                    INSERT INTO Content (path, languageID, content, contentTypeID)
                    SELECT 
                        REPLACE(path, 't/textbookPlaceholder.pdf', ?), 
                        languageID, 
                        content, 
                        contentTypeID 
                    FROM Content 
                    WHERE path = 't/textbookPlaceholder.pdf' OR path LIKE 't/textbookPlaceholder.pdf-%'
                """, arrayOf(dbPath))

                totalReplaced++
            }

            Log.i(TAG, "Successfully replaced $totalReplaced books with placeholders.")
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error replacing books with placeholders", e)
        } finally {
            db?.endTransaction()
            db?.close()
        }
    }

    private fun insertContentChunked(
        db: SQLiteDatabase,
        basePath: String,
        payload: ByteArray,
        contentTypeId: Long
    ) {
        val chunkSize = 1024 * 1024
        if (payload.size < chunkSize) {
            insertContentRow(db, basePath, payload, contentTypeId)
            return
        }

        var offset = 0
        var fragment = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            val slice = payload.copyOfRange(offset, end)
            val path = if (fragment == 0) basePath else "$basePath-$fragment"
            insertContentRow(db, path, slice, contentTypeId)
            offset = end
            fragment++
        }
        if (payload.size % chunkSize == 0) {
            insertContentRow(db, "$basePath-$fragment", ByteArray(0), contentTypeId)
        }
    }

    private fun insertContentRow(
        db: SQLiteDatabase,
        path: String,
        blob: ByteArray,
        contentTypeId: Long
    ) {
        val values = ContentValues().apply {
            put("path", path)
            put("content", blob)
            put("contentTypeID", contentTypeId)
            put("languageId", 1)
        }
        db.insertOrThrow("Content", null, values)
    }

    private lateinit var context: PluginContext

    private val TAG = "BookshelfPlugin"

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        Log.i(TAG, "Bookshelf plugin initialized")
        return true
    }

    override fun activate(): Boolean {
        Log.i(TAG, "Bookshelf plugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        Log.i(TAG, "Bookshelf plugin deactivated")
        return true
    }

    override fun dispose() {}
}