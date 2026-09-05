package org.appdevforall.getaimodels.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.itsaky.androidide.plugins.PluginLogger
import org.appdevforall.getaimodels.catalog.CatalogEntry
import java.io.File

/** One row of DownloadManager's cursor, copied out so no Cursor escapes this file. */
data class DownloadSnapshot(
    val id: Long,
    val status: Int,
    val reason: Int,
    val bytesSoFar: Long,
    val totalBytes: Long,
    val localUri: String?
)

/** Where a finished download's bytes are, once DownloadManager's columns have been interpreted. */
sealed interface DownloadedLocation {

    /** A real filesystem path: hashable, recordable across restarts, and deletable. */
    data class OnDisk(val path: String) : DownloadedLocation

    /**
     * Reachable only through the downloads provider. Hashable through a ContentResolver, but with no
     * path to persist or delete, so the row's badge will not survive a restart.
     */
    data class ViaProvider(val uri: String) : DownloadedLocation
}

/**
 * Works out where a finished download landed, from `COLUMN_LOCAL_URI` and `COLUMN_LOCAL_FILENAME`.
 * Uses [java.net.URI] rather than `android.net.Uri` so the scheme handling is pure and
 * unit-testable off-device.
 */
object DownloadedFileResolver {

    /**
     * Resolution runs in three tiers, because which columns DownloadManager populates varies with
     * the platform version:
     *
     * 1. a `file://` [localUri] - the usual case for a destination in the public Downloads dir;
     * 2. [localFileName], which still carries the absolute path when the URI came back as
     *    `content://`;
     * 3. a `content://` [localUri] on its own: hashable, but pathless.
     *
     * A `content://` URI must never reach [File]: its path (`/all_downloads/42`) is not a filesystem
     * path, and treating it as one reported every finished download as missing.
     */
    fun resolve(localUri: String?, localFileName: String?): DownloadedLocation? {
        val uri = localUri?.takeIf { it.isNotBlank() }
            ?.let { runCatching { java.net.URI(it) }.getOrNull() }

        // getPath() percent-decodes, so a name with spaces resolves to the real file.
        if (uri != null && uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.takeIf { it.isNotBlank() }?.let { return DownloadedLocation.OnDisk(it) }
        }

        localFileName?.takeIf { it.isNotBlank() }
            ?.let { return DownloadedLocation.OnDisk(it) }

        if (uri != null && uri.scheme.equals("content", ignoreCase = true)) {
            return DownloadedLocation.ViaProvider(uri.toString())
        }
        return null
    }
}

/**
 * Everything that talks to the platform DownloadManager: request building, the cursor read, opening a
 * finished download's bytes, and removal. Keeps Binder, Cursor and ContentResolver handling out of
 * [ModelDownloader], which orchestrates state.
 */
class DownloadManagerClient(
    private val appContext: Context,
    private val logger: PluginLogger
) {

    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * Queues [entry] and returns its download id; throws whatever DownloadManager throws.
     *
     * Touches disk (the destination is stat-ed) and inserts into the downloads provider over Binder,
     * so callers must be off the main thread.
     */
    fun enqueue(entry: CatalogEntry, allowOverMetered: Boolean): Long {
        val request = DownloadManager.Request(Uri.parse(entry.url))
            .setTitle(entry.name)
            .setDescription("${entry.quantization} - ${entry.fileName}")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, entry.fileName)
            .setAllowedOverMetered(allowOverMetered)
            .setAllowedOverRoaming(false)
        return downloadManager.enqueue(request)
    }

    /** Cancels a download and deletes its file, partial or complete. */
    fun remove(downloadId: Long) {
        downloadManager.remove(downloadId)
    }

    /**
     * What the gate should hash for a finished [row], or null when the download cannot be located at
     * all. A pathless target still verifies; it just cannot be recorded or deleted afterwards.
     */
    fun verifyTarget(row: DownloadSnapshot, entry: CatalogEntry): VerifyTarget? {
        // Tier 2 costs a second query and touches a column that can throw, so it is only reached
        // when the URI alone did not already yield a path.
        val location = DownloadedFileResolver.resolve(row.localUri, null)
            ?.takeIf { it is DownloadedLocation.OnDisk }
            ?: DownloadedFileResolver.resolve(row.localUri, legacyLocalFileName(row.id))

        return when (location) {
            is DownloadedLocation.OnDisk -> VerifyTarget.ofFile(File(location.path))

            is DownloadedLocation.ViaProvider -> VerifyTarget(
                displayName = entry.fileName,
                path = null
            ) {
                val uri = Uri.parse(location.uri)
                appContext.contentResolver.openInputStream(uri)
                    ?: error("the downloads provider returned no stream for $uri")
            }

            null -> null
        }
    }

    /**
     * `COLUMN_LOCAL_FILENAME` for one download, or null when it cannot be read.
     *
     * Deprecated since API 24, and on Android 10+ reading it throws once the provider actually has a
     * path to report. It is therefore read here - once, for a download that has already finished, and
     * inside a catch - never in [snapshot]. Reading it per poll froze every row: the first poll saw the
     * column still NULL and succeeded, and every poll after the path appeared threw instead of
     * reporting progress.
     */
    @Suppress("DEPRECATION")
    private fun legacyLocalFileName(downloadId: Long): String? = try {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.stringOrNull(DownloadManager.COLUMN_LOCAL_FILENAME)
            } else {
                null
            }
        }
    } catch (t: Throwable) {
        logger.info("COLUMN_LOCAL_FILENAME is unreadable on this platform: ${t.message}")
        null
    }

    /** Current state of [ids]; ids DownloadManager no longer knows about are simply absent. */
    fun snapshot(ids: LongArray): List<DownloadSnapshot> {
        if (ids.isEmpty()) return emptyList()
        val rows = mutableListOf<DownloadSnapshot>()
        downloadManager.query(DownloadManager.Query().setFilterById(*ids))?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += DownloadSnapshot(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    ),
                    reason = cursor.longOrZero(DownloadManager.COLUMN_REASON).toInt(),
                    bytesSoFar = cursor.longOrZero(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    totalBytes = cursor.longOrZero(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    localUri = cursor.stringOrNull(DownloadManager.COLUMN_LOCAL_URI)
                )
            }
        }
        return rows
    }

    private fun android.database.Cursor.longOrZero(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}
