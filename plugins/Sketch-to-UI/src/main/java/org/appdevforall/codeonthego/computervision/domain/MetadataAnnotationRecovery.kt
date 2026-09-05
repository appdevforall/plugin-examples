package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.metadata.recovery.ImageViewMetadataRecovery
import org.appdevforall.codeonthego.computervision.domain.metadata.recovery.ParsedMetadata
import org.appdevforall.codeonthego.computervision.domain.metadata.recovery.PasswordMetadataRecovery
import org.appdevforall.codeonthego.computervision.domain.metadata.recovery.RecoveredAnnotationFormatter
import org.appdevforall.codeonthego.computervision.domain.metadata.recovery.SameBlockIdRecovery
import org.appdevforall.codeonthego.computervision.domain.metadata.recovery.SamePrefixDimensionRecovery
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser

/**
 * Recovers metadata attributes using only evidence from the same widget or same sketch-prefix group.
 *
 * This stage runs after margin annotations are grouped by widget tag. It keeps recovery scoped so
 * noisy OCR from one control cannot rewrite unrelated controls.
 */
internal object MetadataAnnotationRecovery {
    fun resolve(annotations: Map<String, String>): Map<String, String> {
        if (annotations.isEmpty()) return annotations

        val parsedByTag = annotations.mapValues { (tag, rawText) ->
            val androidTag = WidgetTagParser.androidTagFor(tag)
            ParsedMetadata(
                tag = tag,
                androidTag = androidTag,
                rawText = rawText,
                attributes = FuzzyAttributeParser.parse(rawText, androidTag)
            )
        }
        val dimensionsByPrefix = SamePrefixDimensionRecovery.findFallbacks(parsedByTag.values)

        return parsedByTag.mapValues { (_, metadata) ->
            val recoveredAttributes = metadata.attributes.toMutableMap()
            SamePrefixDimensionRecovery.recover(
                metadata = metadata,
                fallbackDimensions = dimensionsByPrefix[metadata.prefix].orEmpty(),
                destination = recoveredAttributes
            )
            SameBlockIdRecovery.resolve(metadata)?.let { recoveredAttributes[it.first] = it.second }
            PasswordMetadataRecovery.recover(metadata, recoveredAttributes)
            ImageViewMetadataRecovery.recover(metadata, recoveredAttributes)
            RecoveredAnnotationFormatter.format(metadata, recoveredAttributes)
        }
    }
}
