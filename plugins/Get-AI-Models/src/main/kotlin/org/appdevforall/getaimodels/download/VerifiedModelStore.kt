package org.appdevforall.getaimodels.download

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** What was verified, and the facts needed to cheaply re-check it after a restart. */
data class VerifiedModel(
    /** Absolute path written to; DownloadManager may have suffixed the catalogued name. */
    val path: String,
    /** Size at verification time; a stat against this catches every truncated file for free. */
    val sizeBytes: Long,
    val verifiedAtEpochMs: Long
) {

    fun toJson(): String = JSONObject()
        .put(KEY_PATH, path)
        .put(KEY_SIZE, sizeBytes)
        .put(KEY_VERIFIED_AT, verifiedAtEpochMs)
        .toString()

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_SIZE = "sizeBytes"
        private const val KEY_VERIFIED_AT = "verifiedAtEpochMs"

        /** Null for anything unreadable: a stale or hand-edited record is dropped, never crashes. */
        fun fromJson(raw: String): VerifiedModel? = runCatching {
            val o = JSONObject(raw)
            VerifiedModel(
                path = o.getString(KEY_PATH),
                sizeBytes = o.getLong(KEY_SIZE),
                verifiedAtEpochMs = o.optLong(KEY_VERIFIED_AT, 0L)
            )
        }.getOrNull()?.takeIf { it.path.isNotBlank() && it.sizeBytes > 0 }
    }
}

enum class DiskStatus {
    /** Nothing at the recorded path - the user moved or deleted it. */
    ABSENT,

    /** Present, but not the size we verified - usually replaced or truncated. */
    SIZE_MISMATCH,

    /** Same path, same length as when the SHA-256 passed. */
    MATCHES
}

/**
 * The record side of the SHA-256 gate, as an interface so [ModelFileGate] can be unit-tested with an
 * in-memory fake instead of Android's SharedPreferences.
 */
interface ModelRecordStore {
    suspend fun all(): Map<String, VerifiedModel>
    suspend fun put(entryId: String, record: VerifiedModel)
    suspend fun remove(entryId: String)
}

/** One stat call. Pure enough to unit-test against temp files. */
object DiskCheck {
    fun status(path: String, expectedSizeBytes: Long): DiskStatus {
        val file = File(path)
        return when {
            !file.isFile -> DiskStatus.ABSENT
            file.length() != expectedSizeBytes -> DiskStatus.SIZE_MISMATCH
            else -> DiskStatus.MATCHES
        }
    }
}

/**
 * Remembers which entries passed the SHA-256 gate, in the plugin's own SharedPreferences (one JSON
 * string per entry id). Persisted rather than recomputed because hashing costs seconds to minutes.
 * Every method suspends onto [Dispatchers.IO], so no caller can touch the disk from the main thread.
 */
class VerifiedModelStore(
    private val preferencesProvider: () -> SharedPreferences
) : ModelRecordStore {

    private val initMutex = Mutex()

    @Volatile
    private var cached: SharedPreferences? = null

    // Mutex-guarded so concurrent first callers cannot each open a SharedPreferences instance.
    private suspend fun preferences(): SharedPreferences =
        cached ?: initMutex.withLock {
            cached ?: withContext(Dispatchers.IO) { preferencesProvider() }.also { cached = it }
        }

    override suspend fun all(): Map<String, VerifiedModel> = withContext(Dispatchers.IO) {
        preferences().all.mapNotNull { (entryId, raw) ->
            (raw as? String)?.let(VerifiedModel::fromJson)?.let { entryId to it }
        }.toMap()
    }

    /** Uses apply(): the in-memory map updates at once and the disk write never blocks a caller. */
    override suspend fun put(entryId: String, record: VerifiedModel) {
        withContext(Dispatchers.IO) {
            preferences().edit().putString(entryId, record.toJson()).apply()
        }
    }

    override suspend fun remove(entryId: String) {
        withContext(Dispatchers.IO) {
            preferences().edit().remove(entryId).apply()
        }
    }
}
