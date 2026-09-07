package org.appdevforall.codeonthego.computervision.domain.model

import android.graphics.RectF

data class DetectionResult(
    val boundingBox: RectF,
    val label: String,
    val score: Float,
    var text: String = "",
    val isYolo: Boolean = true,
    val region: SketchRegion? = null,
    val metadataSource: MetadataOcrSource? = null
)

enum class MetadataOcrSource {
    MARGIN_CROP,
    FULL_IMAGE
}
