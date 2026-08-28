package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * What a selected model file is called and how big it is.
 *
 * @property displayName the name to show the user; never blank
 * @property sizeBytes the file's size, or null when it could not be established — the pre-flight
 *   then has nothing to weigh and skips the check rather than guessing
 */
data class ModelFileInfo(val displayName: String, val sizeBytes: Long?)

/**
 * Reads a selected model file's metadata and bytes, whether it came from the document picker as a
 * `content://` URI or from a saved filesystem path. An interface, so the memory pre-flight can be
 * exercised against ordinary files without a device.
 */
interface ModelFileSource {

    /**
     * Name and size together — for a content URI that is one provider query, where asking
     * separately costs two IPC round trips for one row. Do NOT call on the main thread.
     *
     * @param context supplies the resolver that holds the picker's permission grant
     * @param uriString the selected model, as a `content://` URI or a filesystem path
     */
    fun info(context: Context, uriString: String): ModelFileInfo

    /** Opens the model for reading; null when it cannot be opened. Not for the main thread. */
    fun openStream(context: Context, uriString: String): InputStream?

    /**
     * Whether the model can still be opened right now. A configured model can go away underneath
     * the settings screen — deleted, unmounted, or its read grant revoked — and the stored path
     * says nothing about that, so the screen has to ask. Reports rather than logs: a model that is
     * gone is an answer, not a lookup failure. Not for the main thread.
     */
    fun isReadable(context: Context, uriString: String): Boolean

    /** Decoded last path segment — a cheap name that at least avoids raw `%3A` escapes. */
    fun fallbackDisplayName(uriOrPath: String): String

    /**
     * Turn the picker's one-off read grant for [uriString] into a persistable one, so the model is
     * still readable after the IDE is restarted — nothing is copied into private storage, so that
     * grant is the only thing keeping it reachable (ADFA-5253). A no-op for a filesystem path.
     *
     * @return true when the model will still be readable after a restart
     */
    fun persistAccess(context: Context, uriString: String): Boolean

    /**
     * Give back the persistable read grant the picker took for [uriString], for a model the user
     * ended up not keeping — the grant table has a hard per-app limit. A no-op for a filesystem
     * path, and for a grant that was never held.
     */
    fun releaseAccess(context: Context, uriString: String)
}

/**
 * [ModelFileSource] over the document provider and the filesystem.
 *
 * Every lookup degrades rather than throwing: an unnamed file falls back to its path, and an
 * unknown size is reported as unknown. A model the user picked is not a place to fail hard.
 *
 * @param onError reports a failed lookup, so a silently skipped pre-flight can still be explained
 */
class ContentModelFileSource(
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : ModelFileSource {

    override fun info(context: Context, uriString: String): ModelFileInfo =
        if (uriString.startsWith(CONTENT_SCHEME)) {
            documentInfo(context, uriString) ?: ModelFileInfo(fallbackDisplayName(uriString), null)
        } else {
            ModelFileInfo(fallbackDisplayName(uriString), fileSize(uriString))
        }

    override fun openStream(context: Context, uriString: String): InputStream? = try {
        if (uriString.startsWith(CONTENT_SCHEME)) {
            context.contentResolver.openInputStream(Uri.parse(uriString))
        } else {
            File(uriString).takeIf { it.isFile }?.inputStream()
        }
    } catch (e: Exception) {
        onError("could not open $uriString", e)
        null
    }

    override fun isReadable(context: Context, uriString: String): Boolean = try {
        if (uriString.startsWith(CONTENT_SCHEME)) {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } ?: false
        } else {
            File(uriString).let { it.isFile && it.canRead() }
        }
    } catch (e: Exception) {
        // Deleted, unmounted, or the grant is gone — all of which mean the same thing here.
        false
    }

    override fun fallbackDisplayName(uriOrPath: String): String =
        (try {
            Uri.decode(uriOrPath)
        } catch (e: Exception) {
            uriOrPath
        }).substringAfterLast('/')

    override fun persistAccess(context: Context, uriString: String): Boolean {
        if (!uriString.startsWith(CONTENT_SCHEME)) return true
        return try {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            true
        } catch (e: Exception) {
            // A provider that hands out non-persistable grants, or a grant table that is full.
            onError("could not persist the read grant for $uriString", e)
            false
        }
    }

    override fun releaseAccess(context: Context, uriString: String) {
        if (!uriString.startsWith(CONTENT_SCHEME)) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Never held, or already released — nothing is broken either way.
            onError("could not release the read grant for $uriString", e)
        }
    }

    /** One query for both columns; null when the provider answered with neither. */
    private fun documentInfo(context: Context, uriString: String): ModelFileInfo? = try {
        context.contentResolver
            .query(Uri.parse(uriString), arrayOf(NAME_COLUMN, SIZE_COLUMN), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(NAME_COLUMN)
                val sizeIndex = cursor.getColumnIndex(SIZE_COLUMN)
                val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getString(it) }
                    ?.takeIf { it.isNotBlank() }
                val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getLong(it) }
                    ?.takeIf { it > 0L }
                ModelFileInfo(name ?: fallbackDisplayName(uriString), size)
            }
    } catch (e: Exception) {
        onError("could not read the metadata of $uriString", e)
        null
    }

    private fun fileSize(path: String): Long? = try {
        File(path).length().takeIf { it > 0L }
    } catch (e: Exception) {
        onError("could not read the size of $path", e)
        null
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        val NAME_COLUMN: String = OpenableColumns.DISPLAY_NAME
        val SIZE_COLUMN: String = OpenableColumns.SIZE
    }
}
