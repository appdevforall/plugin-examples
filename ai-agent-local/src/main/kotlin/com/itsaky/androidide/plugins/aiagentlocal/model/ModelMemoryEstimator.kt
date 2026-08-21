package com.itsaky.androidide.plugins.aiagentlocal.model

import com.itsaky.androidide.plugins.aiagentlocal.model.GgufHeader

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
    /** Saturating: [loadBytes] is a queried file size, and a bogus one must not wrap into "it fits". */
    val totalBytes: Long
        get() = if (loadBytes > Long.MAX_VALUE - runBytes) Long.MAX_VALUE else loadBytes + runBytes
}

/**
 * Estimates the memory a `.gguf` model needs, from its size and its declared shape. Pure and
 * Android-free, so the arithmetic is unit-testable. The context and cache type it measures at are
 * the caller's — pass what [ModelContextResolver] resolved, or it describes an allocation nothing
 * makes. See ADFA-5187 and ADFA-5188.
 */
object ModelMemoryEstimator {

    /** Graph and compute buffers every load allocates; see [ModelMemory.RUN_BUFFER_BYTES]. */
    private const val COMPUTE_BUFFER_BYTES = ModelMemory.RUN_BUFFER_BYTES

    /** Floor for the size-based fallback; the same allowance the refusal itself uses. */
    private const val MIN_FALLBACK_RUN_BYTES = ModelMemory.RUN_BUFFER_BYTES

    /**
     * Ceilings on the header's shape values, each far above the largest real model. They exist so a
     * corrupt or crafted file cannot wrap the KV-cache product: within them it stays below 2^57, and
     * an out-of-range value falls back to the size-based heuristic instead of a wrong estimate.
     */
    private const val MAX_LAYERS = 1L shl 10
    private const val MAX_HEADS = 1L shl 12
    private const val MAX_WIDTH = 1L shl 20

    /**
     * @param fileSizeBytes the model file's size, or null when it is unknown
     * @param header the model's metadata, or null when it could not be read
     * @param contextTokens the context the load will be given; required, because a default here
     *   would silently describe an allocation nobody makes. [ModelContextResolver] supplies it.
     * @param kvType the cache type the load will be given; required for the same reason, and from
     *   the same [ModelContextResolver] answer, since it halves what a cached token costs
     * @return the estimate, or null when there is nothing to base one on
     */
    fun estimate(
        fileSizeBytes: Long?,
        header: GgufHeader?,
        contextTokens: Int,
        kvType: KvCacheType,
    ): MemoryEstimate? {
        if (fileSizeBytes == null || fileSizeBytes <= 0L) return null
        val kvCacheBytes = header?.let { kvCacheBytes(it, contextTokens, kvType) }
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
     * KV cache size for a full context of [contextTokens]. Null unless every value it needs is
     * present and within its ceiling, or the context is not positive.
     */
    private fun kvCacheBytes(header: GgufHeader, contextTokens: Int, kvType: KvCacheType): Long? {
        if (contextTokens <= 0) return null
        val perToken = kvBytesPerToken(header, kvType) ?: return null
        return perToken * contextTokens
    }

    /**
     * What one cached position costs: one key and one value entry per kv head, per layer. The
     * factor [ContextSizePolicy] divides a RAM budget by, so sizing and estimate cannot drift apart.
     * Stays under 2^44 within the ceilings below, so any context the policy returns fits a Long.
     *
     * @param header the model's metadata
     * @param kvType the type the cache will be stored as
     * @return bytes of KV cache per token, or null if the header does not say enough
     */
    internal fun kvBytesPerToken(header: GgufHeader, kvType: KvCacheType = KvCacheType.F16): Long? {
        val layers = header.blockCount?.within(MAX_LAYERS) ?: return null
        val heads = header.headCount?.within(MAX_HEADS) ?: return null
        // Grouped-query attention caches only the kv heads; absent means one per head (plain MHA).
        val kvHeads = (header.headCountKv ?: heads).within(MAX_HEADS) ?: return null
        val (keyWidth, valueWidth) = headWidths(header) ?: return null
        return kvType.bytesFor(layers * kvHeads * (keyWidth + valueWidth))
    }

    /**
     * The per-head key and value widths, each either declared or derived. Also what decides whether
     * a quantized cache is possible at all, since it needs both to divide into whole blocks.
     *
     * @param header the model's metadata
     * @return key width to value width, or null if the header does not say enough
     */
    internal fun headWidths(header: GgufHeader): Pair<Long, Long>? {
        val keyWidth = header.keyLength?.within(MAX_WIDTH) ?: defaultHeadWidth(header) ?: return null
        val valueWidth = header.valueLength?.within(MAX_WIDTH) ?: defaultHeadWidth(header) ?: return null
        return keyWidth to valueWidth
    }

    /** The value when it is positive and no larger than [ceiling]; null when it is neither. */
    private fun Long.within(ceiling: Long): Long? = takeIf { it > 0L && it <= ceiling }

    /**
     * Head width where the model does not state one: the width split evenly across the heads. Only
     * a default — gemma-3, for one, declares a key/value width that is not this quotient — so it is
     * used solely for the models that leave `attention.key_length` and `.value_length` out.
     */
    private fun defaultHeadWidth(header: GgufHeader): Long? {
        val embedding = header.embeddingLength?.within(MAX_WIDTH) ?: return null
        val heads = header.headCount?.within(MAX_HEADS) ?: return null
        return (embedding / heads).within(MAX_WIDTH)
    }
}
