package org.appdevforall.codeonthego.computervision.domain.metadata.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns

internal object ImageViewMetadataRecovery {
    private const val IMAGE_VIEW_TAG = "ImageView"

    fun recover(metadata: ParsedMetadata, destination: MutableMap<String, String>) {
        if (metadata.androidTag != IMAGE_VIEW_TAG) return
        val id = destination[AttributeKey.ID.xmlName] ?: return
        val suffix = AttributeRegexPatterns.COMPACT_IMAGE_ID.matchEntire(id)?.groupValues?.get(1) ?: return
        val drawableName = destination[AttributeKey.SRC.xmlName]
            ?.substringAfterLast('/')
            ?.lowercase()
            ?: return
        if (suffix == drawableName) {
            destination[AttributeKey.ID.xmlName] = "img_$suffix"
        }
    }
}
