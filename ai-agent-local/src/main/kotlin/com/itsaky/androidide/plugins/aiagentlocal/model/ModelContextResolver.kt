package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * The context size one model load should get, and the header it was decided from.
 *
 * @property contextTokens the context to load with; always a value [ContextSizePolicy] returned
 * @property header the model's parsed metadata, or null when it could not be read
 */
internal data class ModelContextSize(
    val contextTokens: Int,
    val header: GgufHeader?,
) {

    /** The context the model claims to support, or null when the header did not say. */
    val advertisedTokens: Long? get() = header?.contextLength
}

/**
 * Decides how large a context a given model gets on this device, from the header someone else
 * already read. Pure, so the load path can take its free-RAM reading after an unload and still
 * price the same header the embedding-model guard used. See ADFA-5187.
 */
internal object ModelContextResolver {

    /**
     * Fails open by construction, with no error path of its own: a null header is what
     * [GgufHeaderReader.read] returns for anything it could not open or parse, and
     * [ContextSizePolicy.choose] answers its default for one.
     *
     * @param header the model's parsed metadata, or null when it could not be read
     * @param availableBytes free RAM in bytes, or null when it could not be read
     * @param modelSizeBytes the model file's size in bytes, or null when it could not be read; the
     *   weights are charged against free RAM before the KV cache gets a budget
     * @return the context to load with, and the header behind it
     */
    fun resolve(
        header: GgufHeader?,
        availableBytes: Long?,
        modelSizeBytes: Long?,
    ): ModelContextSize = ModelContextSize(
        contextTokens = ContextSizePolicy.choose(header, availableBytes, modelSizeBytes),
        header = header,
    )
}
