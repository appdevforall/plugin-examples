package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import java.util.Collections
import java.util.IdentityHashMap

object TextInputDetectionCollapser {
    private const val TEXT_ENTRY_LABEL = "text_entry_box"
    private const val TEXT_ENTRY_CONTAINMENT_THRESHOLD = 0.85f
    private const val TEXT_ENTRY_IOU_THRESHOLD = 0.50f

    /** Selects the largest, then highest-confidence detection from each overlapping input group. */
    fun collapse(candidates: List<DetectionResult>): List<DetectionResult> {
        val textInputs = candidates.filter { it.label == TEXT_ENTRY_LABEL }
        if (textInputs.size <= 1) return candidates

        val collapsedInputs = textInputs.connectedComponents()
            .map { component -> component.maxWith(compareBy<DetectionResult> { it.area }.thenBy { it.score }) }
            .toIdentitySet()

        return candidates.filter { candidate ->
            candidate.label != TEXT_ENTRY_LABEL || candidate in collapsedInputs
        }
    }

    private fun List<DetectionResult>.connectedComponents(): List<List<DetectionResult>> {
        val visited: MutableSet<DetectionResult> = Collections.newSetFromMap(IdentityHashMap())

        return mapNotNull { input ->
            if (input in visited) return@mapNotNull null

            val component = mutableListOf<DetectionResult>()
            val pending = ArrayDeque<DetectionResult>()
            pending.add(input)
            visited.add(input)

            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                component.add(current)

                for (candidate in this) {
                    if (candidate in visited || !current.overlapsTextInputDuplicate(candidate)) continue
                    visited.add(candidate)
                    pending.add(candidate)
                }
            }

            component
        }
    }

    private fun Iterable<DetectionResult>.toIdentitySet(): MutableSet<DetectionResult> {
        return Collections.newSetFromMap(IdentityHashMap<DetectionResult, Boolean>()).also { set ->
            set.addAll(this)
        }
    }

    /** Compares containment and intersection-over-union ratios to detect duplicate text inputs. */
    private fun DetectionResult.overlapsTextInputDuplicate(other: DetectionResult): Boolean {
        if (this === other) return true
        val intersectionWidth = minOf(boundingBox.right, other.boundingBox.right) -
            maxOf(boundingBox.left, other.boundingBox.left)
        val intersectionHeight = minOf(boundingBox.bottom, other.boundingBox.bottom) -
            maxOf(boundingBox.top, other.boundingBox.top)
        if (intersectionWidth <= 0f || intersectionHeight <= 0f) return false

        val intersectionArea = intersectionWidth * intersectionHeight
        val smallerArea = minOf(area, other.area)
        val unionArea = area + other.area - intersectionArea
        val containedOverlap = intersectionArea / smallerArea
        val intersectionOverUnion = intersectionArea / unionArea

        return containedOverlap >= TEXT_ENTRY_CONTAINMENT_THRESHOLD ||
            intersectionOverUnion >= TEXT_ENTRY_IOU_THRESHOLD
    }

    /** Calculates the detection bounding-box area. */
    private val DetectionResult.area: Float
        get() = (boundingBox.right - boundingBox.left) * (boundingBox.bottom - boundingBox.top)
}
