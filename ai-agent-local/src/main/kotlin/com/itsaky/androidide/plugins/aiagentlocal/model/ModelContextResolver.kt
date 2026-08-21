package com.itsaky.androidide.plugins.aiagentlocal.model

import java.io.InputStream

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
 * Decides how large a context a given model gets on this device: reads the model's GGUF header and
 * hands it to [ContextSizePolicy]. Owning both steps lets the load path and the pre-load memory
 * warning derive their numbers the same way, and stays Android-free to test. See ADFA-5187.
 */
internal object ModelContextResolver {

    /**
     * Fails open by construction, with no error path of its own: [GgufHeaderReader.read] turns
     * anything thrown while opening or parsing into a null header, and [ContextSizePolicy.choose]
     * answers its default for one. Blocking — the header sits at the front of the model file.
     *
     * @param availableBytes free RAM in bytes, or null when it could not be read
     * @param openStream opens the model file, or returns null when there is nothing to open
     * @return the context to load with, and the header behind it
     */
    fun resolve(availableBytes: Long?, openStream: () -> InputStream?): ModelContextSize {
        val header = GgufHeaderReader.read(openStream)
        return ModelContextSize(
            contextTokens = ContextSizePolicy.choose(header, availableBytes),
            header = header,
        )
    }
}
