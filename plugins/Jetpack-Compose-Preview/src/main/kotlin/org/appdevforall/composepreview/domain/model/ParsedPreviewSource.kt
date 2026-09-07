package org.appdevforall.composepreview.domain.model

import org.appdevforall.composepreview.PreviewConfig

data class ParsedPreviewSource(
    val packageName: String,
    val className: String?,
    val previewConfigs: List<PreviewConfig>
)
