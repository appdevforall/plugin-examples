package com.itsaky.androidide.plugins.aiassistant.memory

import com.itsaky.androidide.plugins.aiassistant.util.GgufHeader

/**
 * What a model will cost in memory, split the way it behaves at runtime.
 *
 * @property loadBytes the weights. mmap'd, so they need not *fit*: when they don't, the device
 *   thrashes page cache instead of failing fast, which is the "ten minutes, then an error" report.
 * @property runBytes KV cache and compute buffers. Ordinary allocations, so this part must fit.
 * @property fromHeader true when [runBytes] came from the model's own shape values rather than the
 *   size-based fallback; diagnostics only.
 */
data class MemoryEstimate(
    val loadBytes: Long,
    val runBytes: Long,
    val fromHeader: Boolean,
) {
    val totalBytes: Long get() = loadBytes + runBytes
}

/**
 * Estimates the memory a `.gguf` model needs, from its size and its declared shape. Pure and
 * Android-free, so the arithmetic is unit-testable. The context and batch sizes below are ai-core's,
 * fixed on its native side: an estimate has to model the loader that will actually run.
 */
object ModelMemoryEstimator {

    /**
     * The context every load gets, hard-coded as `ctx_params.n_ctx` in ai-core's `llama-android.cpp`.
     * The KV cache is sized from it, so keep the two in step.
     */
    const val RUNTIME_CONTEXT_TOKENS = 4096L

    /** Two bytes per cached element: f16, the default KV type. */
    private const val KV_BYTES_PER_ELEMENT = 2L

    /**
     * Graph and compute buffers for the 2048-token batch ai-core allocates (`new_batch` in
     * `LLamaAndroid.load`). Not derivable from the header, so it is a flat allowance.
     */
    private const val COMPUTE_BUFFER_BYTES = 256L * 1024 * 1024

    /**
     * Floor for the size-based fallback, matching ai-core's `ModelLoadDiagnostics` headroom so the
     * warning shown before a load and the error shown after one cannot contradict each other.
     */
    private const val MIN_FALLBACK_RUN_BYTES = 256L * 1024 * 1024

    /**
     * @param fileSizeBytes the model file's size, or null when it is unknown
     * @param header the model's metadata, or null when it could not be read
     * @return the estimate, or null when there is nothing to base one on
     */
    fun estimate(fileSizeBytes: Long?, header: GgufHeader?): MemoryEstimate? {
        if (fileSizeBytes == null || fileSizeBytes <= 0L) return null
        val kvCacheBytes = header?.let(::kvCacheBytes)
        return if (kvCacheBytes != null) {
            MemoryEstimate(fileSizeBytes, kvCacheBytes + COMPUTE_BUFFER_BYTES, fromHeader = true)
        } else {
            // A quarter of the weights is a rough stand-in for a cache we couldn't measure.
            MemoryEstimate(
                loadBytes = fileSizeBytes,
                runBytes = maxOf(MIN_FALLBACK_RUN_BYTES, fileSizeBytes / 4),
                fromHeader = false,
            )
        }
    }

    /**
     * KV cache size for a full context: one key and one value entry per kv head, per layer, per
     * position. Null unless every value it needs is present and usable.
     */
    private fun kvCacheBytes(header: GgufHeader): Long? {
        val layers = header.blockCount?.takeIf { it > 0L } ?: return null
        val heads = header.headCount?.takeIf { it > 0L } ?: return null
        // Grouped-query attention caches only the kv heads; absent means one per head (plain MHA).
        val kvHeads = (header.headCountKv ?: heads).takeIf { it > 0L } ?: return null
        val keyWidth = header.keyLength?.takeIf { it > 0L } ?: defaultHeadWidth(header) ?: return null
        val valueWidth = header.valueLength?.takeIf { it > 0L } ?: defaultHeadWidth(header) ?: return null
        return KV_BYTES_PER_ELEMENT * layers * RUNTIME_CONTEXT_TOKENS * kvHeads * (keyWidth + valueWidth)
    }

    /**
     * Head width where the model does not state one: the width split evenly across the heads. Only
     * a default — gemma-3, for one, declares a key/value width that is not this quotient — so it is
     * used solely for the models that leave `attention.key_length` and `.value_length` out.
     */
    private fun defaultHeadWidth(header: GgufHeader): Long? {
        val embedding = header.embeddingLength?.takeIf { it > 0L } ?: return null
        val heads = header.headCount?.takeIf { it > 0L } ?: return null
        return (embedding / heads).takeIf { it > 0L }
    }
}
