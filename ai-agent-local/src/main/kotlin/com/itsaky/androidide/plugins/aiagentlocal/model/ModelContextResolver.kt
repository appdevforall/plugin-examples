package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.InputStream

/**
 * What one model load should get — context size and KV cache type — and the header behind them.
 *
 * @property contextTokens the context to load with; always a value [ContextSizePolicy] returned
 * @property kvType the type the KV cache will be stored as, which is what that context was sized
 *   against; the two are decided together or they describe different allocations
 * @property fallbackContextTokens the context the same RAM buys under [KvCacheType.F16], for the
 *   native fallback when llama.cpp refuses a quantized cache; equals [contextTokens] when [kvType]
 *   is already [KvCacheType.F16]
 * @property header the model's parsed metadata, or null when it could not be read
 */
internal data class ModelContextSize(
    val contextTokens: Int,
    val kvType: KvCacheType,
    val fallbackContextTokens: Int,
    val header: GgufHeader?,
) {

    /** The context the model claims to support, or null when the header did not say. */
    val advertisedTokens: Long? get() = header?.contextLength
}

/**
 * Decides what one model load gets on this device — context size and KV cache type: reads the
 * model's GGUF header and hands it to [ContextSizePolicy]. Owning every step lets the load path and
 * the pre-load memory warning derive their numbers the same way, and stays Android-free to test.
 * See ADFA-5187 and ADFA-5188.
 */
internal object ModelContextResolver {

    /**
     * Fails open by construction, with no error path of its own: [GgufHeaderReader.read] turns
     * anything thrown while opening or parsing into a null header, and [ContextSizePolicy.choose]
     * answers its default for one. Blocking — the header sits at the front of the model file.
     *
     * @param availableBytes free RAM in bytes, or null when it could not be read
     * @param openStream opens the model file, or returns null when there is nothing to open
     * @return the context and cache type to load with, and the header behind them
     */
    fun resolve(availableBytes: Long?, openStream: () -> InputStream?): ModelContextSize {
        val header = GgufHeaderReader.read(openStream)
        // A quantized cache buys about twice the context, so the type is picked before the size.
        val kvType = ContextSizePolicy.chooseKvCache(header)
        return ModelContextSize(
            contextTokens = ContextSizePolicy.choose(header, availableBytes, kvType),
            kvType = kvType,
            // Sized here rather than scaled natively, so the fallback obeys the one policy that
            // knows the floor, the ceiling and the rounding.
            fallbackContextTokens = ContextSizePolicy.choose(header, availableBytes, KvCacheType.F16),
            header = header,
        )
    }
}
