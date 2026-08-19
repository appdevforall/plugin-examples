package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * Picks the context size (`n_ctx`) one model load gets, from what the model advertises and what the
 * device can spare — the KV cache scales linearly with it and is the largest knob we control. Pure
 * and Android-free, so every boundary is unit-testable off-device. See ADFA-5187.
 */
object ContextSizePolicy {

    /**
     * The context every load got before this policy existed, and now both the fallback for any
     * unreadable input and the floor. Below it the native prompt check starts rejecting
     * conversations that fit today, so a smaller context costs working prompts rather than saving.
     */
    const val DEFAULT_CONTEXT_TOKENS = 4096

    /**
     * Ceiling, whatever the model advertises and the device can afford. Four times the old fixed
     * context and already past the point of diminishing returns, since prefill cost grows with the
     * prompt. Models advertising 32k+ are capped here rather than taken at their word.
     */
    const val MAX_CONTEXT_TOKENS = 16384

    /**
     * Contexts are rounded down to a multiple of this. Purely cosmetic — it keeps the chosen value
     * and the llama.cpp context dump readable instead of reporting a number like 11417.
     */
    private const val GRANULARITY_TOKENS = 256

    /**
     * The share of usable free RAM the KV cache may claim. The IDE and the app being edited draw on
     * the same pool, and `availMem` is a snapshot taken before a load that then takes seconds, so
     * half is left alone rather than sizing to the last free byte.
     */
    private const val KV_BUDGET_DIVISOR = 2L

    /**
     * @param header the model's GGUF metadata, or null when it could not be read
     * @param availableBytes free RAM right now, or null when it could not be read
     * @return the context to configure, always between [DEFAULT_CONTEXT_TOKENS] and
     *   [MAX_CONTEXT_TOKENS] inclusive
     */
    fun choose(header: GgufHeader?, availableBytes: Long?): Int {
        // Each null is a distinct "we don't know"; all of them mean the same fallback.
        if (header == null || availableBytes == null) return DEFAULT_CONTEXT_TOKENS
        val modelTokens = header.contextLength?.takeIf { it > 0L } ?: return DEFAULT_CONTEXT_TOKENS
        // Nothing to weigh below the floor, and no reason to price a cache we would not shrink.
        if (modelTokens <= DEFAULT_CONTEXT_TOKENS) return DEFAULT_CONTEXT_TOKENS

        val perToken = ModelMemoryEstimator.kvBytesPerToken(header)?.takeIf { it > 0L }
            ?: return DEFAULT_CONTEXT_TOKENS

        // Compute buffers come off the top; goes negative on a short device, which the floor absorbs.
        val budgetBytes = (availableBytes - ModelMemory.RUN_BUFFER_BYTES) / KV_BUDGET_DIVISOR
        val affordableTokens = budgetBytes / perToken

        val ceiling = minOf(modelTokens, affordableTokens, MAX_CONTEXT_TOKENS.toLong())
        val rounded = (ceiling / GRANULARITY_TOKENS) * GRANULARITY_TOKENS
        return rounded.coerceIn(DEFAULT_CONTEXT_TOKENS.toLong(), MAX_CONTEXT_TOKENS.toLong()).toInt()
    }
}
