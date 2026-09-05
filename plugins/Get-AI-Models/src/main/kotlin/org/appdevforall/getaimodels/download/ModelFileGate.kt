package org.appdevforall.getaimodels.download

import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.appdevforall.getaimodels.catalog.CatalogEntry
import java.io.File
import java.io.InputStream

/** Opens the bytes to hash. A function so the gate never learns how they are reached. */
fun interface ByteSource {
    /** A fresh stream positioned at the first byte; the gate closes it. */
    fun open(): InputStream
}

/**
 * What to run the gate over. [path] is the file's absolute path when it has one, and null when the
 * bytes are only reachable through the downloads provider - which is hashable but neither
 * recordable across restarts nor deletable from here.
 */
data class VerifyTarget(
    val displayName: String,
    val path: String?,
    val bytes: ByteSource
) {
    companion object {
        fun ofFile(file: File): VerifyTarget =
            VerifyTarget(file.absolutePath, file.absolutePath) { file.inputStream() }
    }
}

/** Outcome of the SHA-256 gate. Deliberately says nothing about UI state or events. */
sealed interface VerifyResult {

    /**
     * The hash matched. [recorded] is false when the target had no path to persist, so the badge
     * will not survive a restart - the file is verified, just not remembered.
     */
    data class Matched(val displayPath: String, val recorded: Boolean) : VerifyResult

    /** The hash did not match. Any record for the entry has already been dropped. */
    object Mismatched : VerifyResult

    /** The path was recorded but nothing is there any more. */
    object Missing : VerifyResult

    /** The bytes exist but could not be read through. */
    data class Unreadable(val reason: String) : VerifyResult
}

/**
 * The SHA-256 gate and the record of what has passed it: hashing, comparison against the catalog,
 * persistence, and deletion of a file this plugin wrote. Deleting a *failed download* is not here -
 * that is DownloadManager's job and lives in [ModelDownloader], which holds the download id.
 *
 * Free of Android APIs beyond the logger, so it unit-tests against temp files and a fake store.
 */
class ModelFileGate(
    private val store: ModelRecordStore,
    private val logger: PluginLogger
) {

    /** Records for every entry that has passed the gate, keyed by entry id. */
    suspend fun records(): Map<String, VerifiedModel> = store.all()

    /** Drops an entry's record without touching the file. */
    suspend fun forget(entryId: String) = store.remove(entryId)

    /**
     * Hashes [target] and compares it with [entry]'s catalogued checksum, persisting a record on a
     * match. A mismatch drops any existing record: whatever it claimed no longer holds.
     */
    suspend fun verify(entry: CatalogEntry, target: VerifyTarget): VerifyResult {
        if (target.path != null && !File(target.path).isFile) {
            store.remove(entry.id)
            return VerifyResult.Missing
        }

        val actual = try {
            withContext(Dispatchers.IO) { target.bytes.open().use { Sha256.of(it) } }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            logger.error("Could not hash ${target.displayName}", t)
            return VerifyResult.Unreadable("could not read the file to verify it")
        }

        if (!actual.equals(entry.sha256, ignoreCase = true)) {
            logger.error(
                "Checksum mismatch for ${target.displayName}: expected ${entry.sha256}, got $actual"
            )
            store.remove(entry.id)
            return VerifyResult.Mismatched
        }

        logger.info("Verified ${target.displayName} (sha256 ${entry.sha256})")
        val path = target.path
            ?: return VerifyResult.Matched(target.displayName, recorded = false)

        val file = File(path)
        store.put(
            entry.id,
            VerifiedModel(
                path = path,
                sizeBytes = file.length(),
                verifiedAtEpochMs = System.currentTimeMillis()
            )
        )
        return VerifyResult.Matched(path, recorded = true)
    }

    /**
     * Deletes a file that passed the gate and drops its record. [path] must come from a record, never
     * from the catalogued file name - a same-named file the user put there is not ours. Already gone
     * counts as deleted: the intent is satisfied and the stale record still clears.
     */
    suspend fun deleteRecorded(entryId: String, path: String): Boolean {
        val file = File(path)
        val gone = withContext(Dispatchers.IO) { !file.exists() || file.delete() }
        if (gone) {
            store.remove(entryId)
            logger.info("Deleted ${file.absolutePath}")
        } else {
            logger.warn("Could not delete ${file.absolutePath}")
        }
        return gone
    }
}
