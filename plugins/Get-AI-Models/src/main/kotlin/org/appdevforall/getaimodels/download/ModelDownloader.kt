package org.appdevforall.getaimodels.download

import android.app.DownloadManager
import android.content.Context
import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.appdevforall.getaimodels.catalog.CatalogEntry
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which DownloadManager download belongs to which catalog entry, polls for completion, and
 * publishes the row state and one-shot events the UI renders. Hashing, the verified-file record and
 * deletion of a recorded file all belong to [ModelFileGate]; this class owns the transfer.
 *
 * Plugin-scoped so closing the tab abandons neither the transfer nor the checksum. Completion is
 * polled rather than broadcast-received; [dispose] cancels everything.
 */
class ModelDownloader(
    appContext: Context,
    private val logger: PluginLogger,
    private val gate: ModelFileGate
) {

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L

        /** Consecutive poll failures after which the rows stop claiming to know what is happening. */
        const val MAX_POLL_FAILURES = 5
    }

    private val downloads = DownloadManagerClient(appContext, logger)

    /**
     * The handler is a backstop, not the error path: every launch below catches its own failures.
     * Without it an unforeseen throw would reach Android's default handler and take the whole IDE
     * down, which is never an acceptable outcome for a failed download.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            logger.error("Unhandled failure in the download scope", t)
        }
    )

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    // extraBufferCapacity so a completion landing while the tab is closed cannot suspend the emitter.
    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** DownloadManager id -> the catalog entry it was started for. */
    private val tracked = ConcurrentHashMap<Long, CatalogEntry>()

    /** Entry ids cancelled before their enqueue returned a download id. */
    private val cancelRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var pollJob: Job? = null

    fun stateOf(entryId: String): DownloadState = _states.value[entryId] ?: DownloadState.Idle

    /**
     * Rebuilds row state from the persisted records, one stat each: gone -> record dropped and Idle,
     * size differs -> [DownloadState.Changed], size matches -> [DownloadState.Verified]. Safe from the
     * main thread. A same-length swap needs [verifyExisting], which this cannot detect.
     */
    fun restore() {
        scope.launch {
            val records = try {
                gate.records()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                logger.warn("Could not read verified-model records", t)
                emptyMap()
            }
            if (records.isEmpty()) return@launch

            val restored = mutableMapOf<String, DownloadState>()
            records.forEach { (entryId, record) ->
                when (DiskCheck.status(record.path, record.sizeBytes)) {
                    DiskStatus.ABSENT -> {
                        logger.info("Verified file for $entryId is gone; dropping the record")
                        gate.forget(entryId)
                    }

                    DiskStatus.SIZE_MISMATCH -> {
                        logger.warn("Verified file for $entryId changed size; needs re-verification")
                        restored[entryId] = DownloadState.Changed(record.path)
                    }

                    DiskStatus.MATCHES -> restored[entryId] = DownloadState.Verified(record.path)
                }
            }

            // One atomic merge: an entry the user started while this read the disk keeps its state.
            _states.update { current ->
                current + restored.filterKeys { it !in current }
            }
        }
    }

    /**
     * Hands [entry] to DownloadManager; callers own the network checks. Passing
     * [allowOverMetered] false (the unmetered case) makes DownloadManager pause rather than spend
     * mobile data if the device later drops to cellular.
     */
    fun enqueue(entry: CatalogEntry, allowOverMetered: Boolean) {
        if (stateOf(entry.id).isBusy) return
        // Published on the calling thread (no disk) so the isBusy guard above rejects a second tap.
        setState(entry.id, DownloadState.Downloading(0L, entry.sizeBytes, Phase.PENDING))
        scope.launch { enqueueBlocking(entry, allowOverMetered) }
    }

    /**
     * The disk-touching half of the handoff, hence off the main thread: the destination is stat-ed and
     * the enqueue inserts into the downloads provider. StrictMode propagates across that Binder call,
     * so running this on the main thread also reported the provider's own SQLite violations as ours.
     */
    private fun enqueueBlocking(entry: CatalogEntry, allowOverMetered: Boolean) {
        val id = try {
            downloads.enqueue(entry, allowOverMetered)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            logger.error("Failed to enqueue ${entry.fileName}", t)
            cancelRequested.remove(entry.id)
            setState(entry.id, DownloadState.Failed(t.message ?: "could not start", false))
            _events.tryEmit(
                DownloadEvent.TransportFailed(entry.id, entry.name, t.message ?: "could not start")
            )
            return
        }

        tracked[id] = entry
        logger.info("Queued ${entry.fileName} as download $id")

        // Cancel tapped before this enqueue landed: [cancel] had no id yet and left a note.
        if (cancelRequested.remove(entry.id)) {
            remove(id, entry)
            return
        }
        startPolling()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var failures = 0
            while (tracked.isNotEmpty()) {
                delay(POLL_INTERVAL_MS)
                try {
                    pollOnce()
                    failures = 0
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    failures++
                    logger.warn("Download poll failed ($failures)", t)
                    // A poll that keeps throwing used to leave every row frozen mid-progress for as
                    // long as the IDE ran, with nothing above a warning to say so. The transfer is
                    // DownloadManager's and continues in the notification; what is lost is our view
                    // of it, and the row now says that instead of showing a number that never moves.
                    if (failures >= MAX_POLL_FAILURES) {
                        logger.error("Abandoning download polling after $failures failures", t)
                        tracked.keys.toList().forEach { id ->
                            tracked.remove(id)?.let {
                                fail(it, "lost track of this download - check your Downloads")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pollOnce() {
        val ids = tracked.keys.toLongArray()
        if (ids.isEmpty()) return

        val rows = downloads.snapshot(ids)
        rows.forEach { row ->
            val entry = tracked[row.id] ?: return@forEach
            when (row.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    tracked.remove(row.id)
                    onDownloadFinished(entry, row)
                }

                DownloadManager.STATUS_FAILED -> {
                    tracked.remove(row.id)
                    fail(entry, "download failed (reason ${row.reason})")
                }

                // Pending/running/paused reported distinctly: the notification is dismissible.
                else -> setState(entry.id, progressOf(row, entry))
            }
        }

        // An id DownloadManager no longer knows about was cleared from the Downloads UI.
        (tracked.keys - rows.map { it.id }.toSet()).forEach { id ->
            tracked.remove(id)?.let { fail(it, "download was cancelled") }
        }
    }

    /**
     * Starts verification of a finished download, or fails the row when the bytes cannot be located.
     * Nothing is guessed from the catalogued name: hashing the wrong file would delete an unrelated
     * same-named file on mismatch.
     */
    private fun onDownloadFinished(entry: CatalogEntry, row: DownloadSnapshot) {
        val target = downloads.verifyTarget(row, entry)
        if (target == null) {
            logger.error("Could not locate download ${row.id} from uri '${row.localUri}'")
            fail(entry, "could not locate the downloaded file")
            return
        }
        // Hashed off the poll loop so a second download's completion is still noticed promptly.
        scope.launch { runGate(entry, target, failedDownloadId = row.id) }
    }

    /** Maps a snapshot row onto the in-flight state the row renders. */
    private fun progressOf(row: DownloadSnapshot, entry: CatalogEntry): DownloadState.Downloading {
        // -1 means the server sent no length; the catalogued size is the one we verify against.
        val total = if (row.totalBytes > 0L) row.totalBytes else entry.sizeBytes
        val phase = when (row.status) {
            DownloadManager.STATUS_PENDING -> Phase.PENDING
            DownloadManager.STATUS_PAUSED -> when (row.reason) {
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> Phase.PAUSED_WAITING_FOR_WIFI
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> Phase.PAUSED_WAITING_FOR_NETWORK
                DownloadManager.PAUSED_WAITING_TO_RETRY -> Phase.PAUSED_WAITING_TO_RETRY
                else -> Phase.PAUSED_UNKNOWN
            }

            else -> Phase.RUNNING
        }
        return DownloadState.Downloading(row.bytesSoFar, total, phase)
    }

    /**
     * Abandons an in-flight download: `DownloadManager.remove` cancels it and deletes the partial
     * file. The only way to stop a download from the IDE, and the only one at all once the system
     * notification has been dismissed.
     */
    fun cancel(entryId: String) {
        val tracking = tracked.entries.firstOrNull { it.value.id == entryId }
        if (tracking == null) {
            // No id yet (enqueue still on IO); note it rather than stranding the row in Downloading.
            if (stateOf(entryId) is DownloadState.Downloading) cancelRequested.add(entryId)
            return
        }
        val downloadId = tracking.key
        val entry = tracking.value
        tracked.remove(downloadId)
        scope.launch { remove(downloadId, entry) }
    }

    /** Drops a download from DownloadManager - this also deletes its partial file. */
    private fun remove(downloadId: Long, entry: CatalogEntry) {
        tracked.remove(downloadId)
        try {
            downloads.remove(downloadId)
        } catch (t: Throwable) {
            logger.warn("Could not remove download $downloadId", t)
        }
        logger.info("Cancelled ${entry.fileName}")
        setState(entry.id, DownloadState.Idle)
        _events.tryEmit(DownloadEvent.Cancelled(entry.id, entry.name))
    }

    /**
     * Re-runs the SHA-256 gate on a file already on disk - the escape hatch for the same-length swap
     * [restore]'s size check cannot see. Never deletes on mismatch: that file was not written by this
     * download, so removing it is the user's call.
     */
    fun verifyExisting(entry: CatalogEntry, path: String) {
        if (stateOf(entry.id).isBusy) return
        setState(entry.id, DownloadState.Verifying)
        scope.launch {
            runGate(entry, VerifyTarget.ofFile(File(path)), failedDownloadId = null)
        }
    }

    /**
     * Deletes a file this plugin downloaded. [path] must come from a persisted record (a
     * [DownloadState.Verified] or [DownloadState.Changed] row), never from the catalogued file name -
     * a same-named file the user put there is not ours.
     */
    fun deleteDownloadedFile(entry: CatalogEntry, path: String) {
        // Refused while hashing: deleting under Sha256.of() surfaces as a confusing read error.
        if (stateOf(entry.id).isBusy) return
        scope.launch {
            val deleted = try {
                gate.deleteRecorded(entry.id, path)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                // A SecurityException from an unwritable path used to reach the default handler.
                logger.error("Could not delete $path", t)
                false
            }
            if (deleted) {
                setState(entry.id, DownloadState.Idle)
                _events.tryEmit(DownloadEvent.Deleted(entry.id, entry.name))
            } else {
                _events.tryEmit(DownloadEvent.DeleteFailed(entry.id, entry.name))
            }
        }
    }

    /**
     * Runs the gate and turns its result into row state and one event. [failedDownloadId] is the
     * download to remove if the checksum fails - non-null only for a fresh download, where a corrupt
     * file is deleted rather than kept. Re-verification of an existing file passes null: it never
     * deletes.
     */
    private suspend fun runGate(
        entry: CatalogEntry,
        target: VerifyTarget,
        failedDownloadId: Long?
    ) {
        setState(entry.id, DownloadState.Verifying)
        val result = try {
            gate.verify(entry, target)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            logger.error("Verification of ${target.displayName} failed outright", t)
            fail(entry, "could not verify the file")
            return
        }

        when (result) {
            is VerifyResult.Matched -> {
                setState(entry.id, DownloadState.Verified(result.displayPath, result.recorded))
                if (!result.recorded) {
                    // Hashed through the downloads provider: correct, but nothing to record.
                    logger.warn(
                        "${entry.fileName} verified without a filesystem path; the badge will " +
                            "not survive a restart and the file cannot be deleted from here"
                    )
                }
                _events.tryEmit(DownloadEvent.Verified(entry.id, entry.name))
            }

            VerifyResult.Missing -> fail(entry, "the file is missing")

            is VerifyResult.Unreadable -> fail(entry, result.reason)

            VerifyResult.Mismatched -> onChecksumMismatch(entry, target, failedDownloadId)
        }
    }

    /**
     * Hard gate on a download: a file that fails the checksum is deleted, never kept. Deletion goes
     * through DownloadManager, which removes the row and the file whether or not we hold a path -
     * with a direct delete as a fallback for a path it declines to clean up.
     */
    private fun onChecksumMismatch(
        entry: CatalogEntry,
        target: VerifyTarget,
        failedDownloadId: Long?
    ) {
        if (failedDownloadId == null) {
            setState(entry.id, DownloadState.Failed("checksum did not match", false))
            _events.tryEmit(DownloadEvent.VerifyFailed(entry.id, entry.name))
            return
        }

        try {
            downloads.remove(failedDownloadId)
        } catch (t: Throwable) {
            logger.warn("Could not remove the failed download $failedDownloadId", t)
        }
        val leftover = target.path?.let(::File)
        if (leftover != null && leftover.exists() && !leftover.delete()) {
            logger.warn("Could not delete corrupt download ${leftover.absolutePath}")
        }
        setState(entry.id, DownloadState.Failed("checksum did not match", true))
        _events.tryEmit(DownloadEvent.ChecksumFailed(entry.id, entry.name))
    }

    private fun fail(entry: CatalogEntry, message: String) {
        logger.warn("${entry.fileName}: $message")
        setState(entry.id, DownloadState.Failed(message, false))
        _events.tryEmit(DownloadEvent.TransportFailed(entry.id, entry.name, message))
    }

    private fun setState(entryId: String, state: DownloadState) {
        // update() rather than a read-modify-write on .value: verifications run concurrently.
        _states.update { it + (entryId to state) }
    }

    fun dispose() {
        scope.cancel()
        pollJob = null
        tracked.clear()
        cancelRequested.clear()
        _states.value = emptyMap()
    }
}
