package org.appdevforall.getaimodels.download

/**
 * Per-entry row state. [Verified] survives a plugin reload because [VerifiedModelStore] persists it
 * and [ModelDownloader.restore] revalidates it against the disk - a badge outliving the file it
 * describes would be worse than no badge.
 */
sealed class DownloadState {

    /** Never started, cancelled, or reset after a failure the user has been told about. */
    object Idle : DownloadState()

    /**
     * Handed to DownloadManager, and self-sufficient enough to render the row without the system
     * notification the user can dismiss. [totalBytes] falls back to the catalogued size when
     * DownloadManager reports an unknown total, so there is always a denominator.
     */
    data class Downloading(
        val bytesSoFar: Long,
        val totalBytes: Long,
        val phase: Phase
    ) : DownloadState() {

        /** 0f..1f, clamped. Drives the fill drawn behind the Cancel button. */
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f
            else (bytesSoFar.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

        val isPaused: Boolean get() = phase != Phase.PENDING && phase != Phase.RUNNING
    }

    /** Download finished, SHA-256 is being computed. */
    object Verifying : DownloadState()

    /**
     * Checksum matched and the file was kept. [onDisk] is false in the one case where DownloadManager
     * reports the finished download only through the downloads provider: the bytes were hashed, but
     * with no filesystem path there is nothing to record for the next restart and nothing this plugin
     * can delete, so [path] is only a name to show and the row offers neither action.
     */
    data class Verified(val path: String, val onDisk: Boolean = true) : DownloadState()

    /**
     * Still at [path] but no longer the size we hashed, so the verification no longer holds. The file
     * is left alone and re-verifying is offered instead.
     */
    data class Changed(val path: String) : DownloadState()

    /**
     * Terminal failure. [checksumMismatch] distinguishes the "downloaded but corrupt, file
     * deleted" case (which offers a re-download) from a transport failure.
     */
    data class Failed(val message: String, val checksumMismatch: Boolean) : DownloadState()

    val isBusy: Boolean get() = this is Downloading || this is Verifying
}

/**
 * Whether a transfer is moving bytes, and if not, why. Mapped from DownloadManager's status and
 * reason columns; these once all rendered as "Downloading...", making an indefinitely paused
 * download indistinguishable from one making progress.
 */
enum class Phase {
    /** Queued, not started yet. */
    PENDING,

    /** Actively transferring. */
    RUNNING,

    /** Began on an unmetered connection, now on a metered one; resumes when Wi-Fi returns. */
    PAUSED_WAITING_FOR_WIFI,

    /** Paused because there is no usable connection at all. */
    PAUSED_WAITING_FOR_NETWORK,

    /** Paused between automatic retries after a transient error. */
    PAUSED_WAITING_TO_RETRY,

    /** Paused for a reason DownloadManager did not classify. */
    PAUSED_UNKNOWN
}

/** One-shot outcome, surfaced to the UI as a dialog or snackbar rather than as row state. */
sealed class DownloadEvent {
    data class Verified(val entryId: String, val modelName: String) : DownloadEvent()

    /** A *download* failed the checksum: the file was deleted and a re-download is offered. */
    data class ChecksumFailed(val entryId: String, val modelName: String) : DownloadEvent()

    /** Re-verification of a file already on disk failed; unlike [ChecksumFailed], nothing is deleted. */
    data class VerifyFailed(val entryId: String, val modelName: String) : DownloadEvent()

    data class Cancelled(val entryId: String, val modelName: String) : DownloadEvent()

    data class Deleted(val entryId: String, val modelName: String) : DownloadEvent()

    data class DeleteFailed(val entryId: String, val modelName: String) : DownloadEvent()

    data class TransportFailed(val entryId: String, val modelName: String, val reason: String) :
        DownloadEvent()
}
