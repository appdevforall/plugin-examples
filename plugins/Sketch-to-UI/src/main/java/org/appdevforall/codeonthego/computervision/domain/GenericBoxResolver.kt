package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

class GenericBoxResolver {
    private companion object {
        const val MIN_DROPDOWN_SYMBOL_SCORE = 0.70f
        private const val SYMBOL_BOX_MARGIN_FACTOR = 0.20f
        private const val SYMBOL_VERTICAL_MARGIN_FACTOR = 0.35f
        private const val LEFT_EDGE_ZONE_END = 0.40f
        private const val RIGHT_EDGE_ZONE_START = 0.60f
    }

    fun resolve(detections: List<DetectionResult>): List<DetectionResult> {
        val dropdownSymbols = detections.filter {
            it.label == DetectionLabels.DROPDOWN_SYMBOL && it.score >= MIN_DROPDOWN_SYMBOL_SCORE
        }

        return detections.mapNotNull { det ->
            when (det.label) {
                DetectionLabels.DROPDOWN_SYMBOL -> null
                DetectionLabels.GENERIC_BOX -> {
                    val hasSymbolNearby = dropdownSymbols.any { symbol ->
                        isAcceptedDropdownSymbol(det.boundingBox, symbol.boundingBox)
                    }
                    det.copy(label = if (hasSymbolNearby) DetectionLabels.DROPDOWN else DetectionLabels.TEXT_ENTRY_BOX)
                }
                else -> det
            }
        }
    }

    private fun isAcceptedDropdownSymbol(box: RectF, symbol: RectF): Boolean {
        val boxWidth = box.right - box.left
        val boxHeight = box.bottom - box.top
        val symbolCenterX = (symbol.left + symbol.right) / 2f
        val symbolCenterY = (symbol.top + symbol.bottom) / 2f

        val horizontalMargin = boxWidth * SYMBOL_BOX_MARGIN_FACTOR
        val verticalMargin = boxHeight * SYMBOL_VERTICAL_MARGIN_FACTOR

        val centerInsideOrVeryClose = symbolCenterX in (box.left - horizontalMargin)..(box.right + horizontalMargin) &&
            symbolCenterY in (box.top - verticalMargin)..(box.bottom + verticalMargin)
        if (!centerInsideOrVeryClose) return false

        val leftEdgeZoneEnd = box.left + (boxWidth * LEFT_EDGE_ZONE_END)
        val rightEdgeZoneStart = box.left + (boxWidth * RIGHT_EDGE_ZONE_START)
        return symbolCenterX <= leftEdgeZoneEnd || symbolCenterX >= rightEdgeZoneStart
    }
}
