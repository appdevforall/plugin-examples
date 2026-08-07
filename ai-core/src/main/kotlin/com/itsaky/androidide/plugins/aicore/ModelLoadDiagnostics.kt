package com.itsaky.androidide.plugins.aicore

import java.io.File

/**
 * Classifies why a native model load failed, as a pure function of the file, free memory, and the
 * native loader's error text, so it is unit-testable off-device. The caller maps the result to a
 * user-facing message from strings.xml (this object holds no Context and no display text).
 * See ADFA "Better error messages …".
 */
object ModelLoadDiagnostics {

    /** Headroom floor: a small model still needs a KV cache, which scales with context, not size. */
    private const val MIN_HEADROOM_BYTES = 256L * 1024 * 1024

    /** Most likely cause of a load failure; the caller resolves each case to a user-facing string. */
    sealed interface Diagnosis {
        data object FileMissing : Diagnosis
        data object FileEmpty : Diagnosis
        data object NotGguf : Diagnosis
        /**
         * @param neededBytes the free-RAM headroom the check required (see [diagnose])
         * @param availableBytes free RAM the OS reported
         */
        data class LowMemory(val neededBytes: Long, val availableBytes: Long) : Diagnosis

        /** A model is already resident in the process-global run loop (a state issue, not the file). */
        data object ModelBusy : Diagnosis

        /** The file is a valid GGUF but the loader couldn't allocate its context/buffers (memory pressure). */
        data object InitializationFailed : Diagnosis

        /** The file is a valid GGUF but the loader rejected the weights themselves (format/quantization/corruption). */
        data object UnsupportedOrCorrupt : Diagnosis
    }

    /**
     * Weights are mmap'd, so the file need not fit in free RAM — only the KV cache and compute
     * buffers must be resident. The memory check therefore tests a conservative headroom, because
     * overestimating it would blame a corrupt model on memory and send users chasing smaller files.
     *
     * @param modelPath resolved filesystem path the native loader was handed
     * @param availableMemoryBytes free RAM reported by the OS, or negative if unknown
     * @param nativeError the load failure's message text, or null when unavailable
     * @return the most likely cause of the load failure
     */
    fun diagnose(modelPath: String, availableMemoryBytes: Long, nativeError: String? = null): Diagnosis {
        val file = File(modelPath)
        if (!file.exists()) return Diagnosis.FileMissing

        val sizeBytes = file.length()
        if (sizeBytes <= 0L) return Diagnosis.FileEmpty
        if (!GgufModelInspector.isGguf(modelPath)) return Diagnosis.NotGguf

        // "Already loaded" is a run-loop state problem, not a file or memory one, so report it
        // before the memory heuristic — otherwise a busy loop is mis-reported as low memory.
        if (indicatesModelBusy(nativeError)) return Diagnosis.ModelBusy

        // Headroom only: a fraction of the file, floored for KV-cache-dominated small models.
        val requiredHeadroomBytes = maxOf(MIN_HEADROOM_BYTES, sizeBytes / 4)
        // Only a NEGATIVE reading means "unknown"; 0 is a genuine out-of-memory reading.
        if (availableMemoryBytes in 0L until requiredHeadroomBytes) {
            return Diagnosis.LowMemory(requiredHeadroomBytes, availableMemoryBytes)
        }

        // The file is a valid GGUF and RAM looks sufficient, so trust the native failure reason:
        // an allocation failure points at memory pressure, anything else at an unsupported/corrupt model.
        return if (indicatesInitFailure(nativeError)) Diagnosis.InitializationFailed
        else Diagnosis.UnsupportedOrCorrupt
    }

    // The markers below mirror the messages thrown by LLamaAndroid.load(); keep them in sync with
    // that file. Matching on text is best-effort — an unrecognized message falls back to
    // UnsupportedOrCorrupt, which is the safe default for a valid-looking file.
    private fun indicatesModelBusy(nativeError: String?): Boolean =
        nativeError?.contains("already loaded", ignoreCase = true) == true

    private fun indicatesInitFailure(nativeError: String?): Boolean {
        val error = nativeError ?: return false
        return error.contains("new_context", ignoreCase = true) ||
            error.contains("new_batch", ignoreCase = true) ||
            error.contains("new_sampler", ignoreCase = true)
    }
}
