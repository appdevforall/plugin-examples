package com.itsaky.androidide.plugins.aicore

/**
 * A backend whose in-flight streaming generation can be cancelled (Stop pressed).
 * Implement this so [LlmInferenceServiceImpl.cancelGeneration] cancels it without
 * a per-type `when` branch.
 */
interface CancellableBackend {
    /** Cancel any in-flight generation. */
    fun cancelStreaming()
}
