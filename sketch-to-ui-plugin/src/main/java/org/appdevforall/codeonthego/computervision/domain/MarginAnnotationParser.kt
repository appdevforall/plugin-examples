package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.MetadataOcrSource
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import kotlin.math.abs

object MarginAnnotationParser {
    /**
     * Extracts canvas UI elements and parses margin annotations, linking them together.
     * * @param detections The full list of detections from YOLO and OCR.
     * @param imageWidth The width of the source image in pixels.
     * @param leftGuidePct The percentage (0.0 to 1.0) defining the left margin boundary.
     * @param rightGuidePct The percentage (0.0 to 1.0) defining the right margin boundary.
     * @return A Pair containing the valid canvas UI detections and a mapped dictionary of [Widget Tag -> Annotation Text].
     */
    fun parse(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Pair<List<DetectionResult>, Map<String, String>> {
        val sanitizedDetections = detections.filterNot { MetadataDetector.isMetadataLabel(it.label) }

        val distribution = distributeDetections(sanitizedDetections, imageWidth, leftGuidePct, rightGuidePct)
        val canvasTags = extractCanvasTags(distribution.canvas)

        val annotationMap = MetadataAnnotationRecovery.resolve(parseMarginsGlobally(
            leftMargin = distribution.leftMargin,
            rightMargin = distribution.rightMargin,
            canvasTags = canvasTags
        ))

        return Pair(distribution.canvas, annotationMap)
    }

    /**
     * Distributes raw detections into three zones: left margin, right margin, and the main canvas.
     * Metadata detections invading the canvas are forcefully pushed back to the margins.
     */
    private fun distributeDetections(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): DetectionDistribution {
        val leftMarginPx = imageWidth * leftGuidePct
        val rightMarginPx = imageWidth * rightGuidePct

        val canvas = mutableListOf<DetectionResult>()
        val leftMargin = mutableListOf<DetectionResult>()
        val rightMargin = mutableListOf<DetectionResult>()

        for (detection in detections) {
            if (detection.isInvalidWidgetTag()) {
                continue
            }
            when (detection.region) {
                SketchRegion.LEFT_METADATA -> {
                    leftMargin.add(detection)
                    continue
                }
                SketchRegion.RIGHT_METADATA -> {
                    rightMargin.add(detection)
                    continue
                }
                SketchRegion.CANVAS -> {
                    canvas.add(detection)
                    continue
                }
                SketchRegion.CROSS_BOUNDARY -> {
                    if (detection.isRenderableCrossBoundaryText()) {
                        canvas.add(detection)
                    }
                    continue
                }
                null -> Unit
            }

            val isMetadata = MetadataDetector.isCanvasMetadata(detection.text)
            val centerX = centerX(detection)

            val assignedRegion = when {
                isMetadata && centerX < (imageWidth / 2f) -> SketchRegion.LEFT_METADATA
                isMetadata && centerX >= (imageWidth / 2f) -> SketchRegion.RIGHT_METADATA
                centerX > leftMarginPx && centerX < rightMarginPx -> SketchRegion.CANVAS
                centerX <= leftMarginPx -> SketchRegion.LEFT_METADATA
                else -> SketchRegion.RIGHT_METADATA
            }
            when (assignedRegion) {
                SketchRegion.LEFT_METADATA -> leftMargin.add(detection)
                SketchRegion.RIGHT_METADATA -> rightMargin.add(detection)
                SketchRegion.CANVAS -> canvas.add(detection)
                SketchRegion.CROSS_BOUNDARY -> Unit
            }
        }

        return DetectionDistribution(canvas, leftMargin, rightMargin)
    }

    /**
     * Identifies and extracts valid widget tags from the canvas detections.
     */
    private fun extractCanvasTags(canvasDetections: List<DetectionResult>): List<Pair<String, DetectionResult>> {
        return canvasDetections.mapNotNull { det ->
            if (!WidgetTagParser.isTag(det.text)) return@mapNotNull null
            WidgetTagParser.extractTag(det.text)?.let { (tag, _) ->
                tag to det
            }
        }
    }

    /**
     * Processes both margins simultaneously to prevent cross-margin collisions.
     * Gathers all explicit annotations first, then resolves all implicit blocks
     * against a shared pool of remaining tags.
     */
    private fun parseMarginsGlobally(
        leftMargin: List<DetectionResult>,
        rightMargin: List<DetectionResult>,
        canvasTags: List<Pair<String, DetectionResult>>
    ): Map<String, String> {
        val leftBlocks = extractBlocksBySource(leftMargin)
        val rightBlocks = extractBlocksBySource(rightMargin)

        val allSourceBlocks = leftBlocks + rightBlocks
        val globalExplicitAnnotations = mergeExplicitAnnotations(allSourceBlocks)

        val allImplicitBlocks = allSourceBlocks.flatMap { it.blocks.implicitBlocks }

        val resolvedImplicitAnnotations = resolveImplicitBlocks(
            implicitBlocks = allImplicitBlocks,
            canvasTags = canvasTags,
            existingAnnotations = globalExplicitAnnotations
        )

        return mergeAnnotations(globalExplicitAnnotations, resolvedImplicitAnnotations)
    }

    private fun extractBlocksBySource(detections: List<DetectionResult>): List<SourcedBlocks> {
        return detections.groupBy { it.metadataSource }
            .map { (source, sourceDetections) ->
                SourcedBlocks(source, extractBlocks(sourceDetections.sortedBy { it.boundingBox.top }))
            }
    }

    private fun mergeExplicitAnnotations(sourceBlocks: List<SourcedBlocks>): MutableMap<String, String> {
        return sourceBlocks
            .sortedBy { it.source.priority }
            .flatMap { sourced ->
                sourced.blocks.explicitAnnotations.map { (tag, annotation) ->
                    tag to SourcedAnnotation(annotation, sourced.source)
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, candidates) -> mergeAnnotationFragments(candidates) }
            .toMutableMap()
    }

    /**
     * Combines duplicate annotations for the same widget while keeping one selected fragment per key.
     *
     * Margin-crop OCR is preferred over full-image OCR, except same-priority dimension fragments can
     * be replaced by a less noisy candidate.
     */
    private fun mergeAnnotationFragments(candidates: List<SourcedAnnotation>): String {
        val fragments = candidates.toSourcedFragments()
        return buildMergedAnnotation(
            keyedFragments = fragments.selectBestKeyedFragments(),
            unkeyedFragments = fragments.uniqueUnkeyedFragments()
        )
    }

    private fun List<SourcedAnnotation>.toSourcedFragments(): List<SourcedFragment> {
        return sortedBy { it.source.priority }.flatMap { candidate ->
            candidate.text.groupAdjacentDimensionFragments().map { fragment ->
                SourcedFragment(
                    text = fragment,
                    key = normalizedAttributeKey(fragment),
                    sourcePriority = candidate.source.priority
                )
            }
        }
    }

    private fun List<SourcedFragment>.selectBestKeyedFragments(): List<SelectedFragment> {
        return filter { it.key != null }
            .groupBy { it.key.orEmpty() }
            .map { (key, fragments) -> fragments.bestFragmentFor(key) }
    }

    private fun List<SourcedFragment>.bestFragmentFor(key: String): SelectedFragment {
        return map { SelectedFragment(it.text, it.sourcePriority) }
            .reduce { currentBest, candidate ->
                if (candidate.isBetterThan(currentBest, key)) candidate else currentBest
            }
    }

    private fun List<SourcedFragment>.uniqueUnkeyedFragments(): List<String> {
        return filter { it.key == null }
            .map { it.text }
            .distinct()
    }

    private fun buildMergedAnnotation(
        keyedFragments: List<SelectedFragment>,
        unkeyedFragments: List<String>
    ): String {
        return (keyedFragments.map { it.text } + unkeyedFragments).joinToString(" | ")
    }

    private fun String.groupAdjacentDimensionFragments(): List<String> {
        val fragments = split('|').map(String::trim).filter(String::isNotEmpty)
        return fragments.fold(mutableListOf()) { grouped, fragment ->
            grouped.appendGroupedFragment(fragment)
            grouped
        }
    }

    private fun MutableList<String>.appendGroupedFragment(fragment: String) {
        val previous = lastOrNull()
        if (previous != null && shouldJoinUnlabeledDimension(previous, fragment)) {
            this[lastIndex] = "$previous $fragment"
        } else {
            add(fragment)
        }
    }

    private fun shouldJoinUnlabeledDimension(previous: String, fragment: String): Boolean {
        return normalizedAttributeKey(previous) in DIMENSION_KEYS &&
            UNLABELED_DIMENSION.matches(fragment)
    }

    private fun normalizedAttributeKey(fragment: String): String? {
        val rawKey = ATTRIBUTE_KEY_PREFIX.find(fragment)?.groupValues?.get(1) ?: return null
        val compactKey = rawKey.lowercase().replace(Regex("[^a-z]"), "")
        return when {
            compactKey.startsWith("layoutwidth") -> AttributeKey.WIDTH.aliases.first()
            compactKey.startsWith("layoutheight") || compactKey.startsWith("layoutheiqht") -> AttributeKey.HEIGHT.aliases.first()
            compactKey in AttributeKey.ID.aliases -> AttributeKey.ID.aliases.first()
            compactKey.isNotBlank() -> compactKey
            else -> null
        }
    }

    /**
     * Merges multiple annotation maps. If a tag exists in multiple maps,
     * their values are combined separated by " | ".
     */
    private fun mergeAnnotations(vararg maps: Map<String, String>): MutableMap<String, String> {
        return maps.flatMap { it.toList() }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.joinToString(" | ") }
            .toMutableMap()
    }

    /**
     * Reads vertically through margin detections and groups them into text blocks.
     * Blocks starting with an explicit tag become explicit annotations, while untagged blocks are stored as implicit.
     * Side-based prefix heuristics are intentionally not applied here because OCR can place
     * a valid explicit tag on the opposite margin from its detected canvas tag.
     */
    private fun extractBlocks(
        sortedDetections: List<DetectionResult>
    ): GroupedBlocks {
        val blocks = GroupedBlocks()
        var currentTag: String? = null
        var currentText = StringBuilder()
        var blockStartY = 0f

        fun saveCurrentBlock() {
            if (currentTag != null) {
                blocks.addExplicitAnnotation(currentTag!!, currentText.toString().trim())
            } else if (currentText.isNotBlank()) {
                blocks.implicitBlocks.add(ParsedBlock(currentText.toString().trim(), blockStartY))
            }
        }

        for (det in sortedDetections) {
            val text = det.text.trim().trimStart('|', ':', ';', '.', ',', '_')
            val extraction = WidgetTagParser.extractTag(text)

            val isExplicitTag = extraction != null

            if (isExplicitTag) {
                saveCurrentBlock()

                currentTag = extraction.first
                currentText = StringBuilder()
                blockStartY = centerY(det)

                val trailing = extraction.second?.trim()
                if (!trailing.isNullOrBlank() && WidgetTagParser.normalizeTagText(trailing) != currentTag) {
                    currentText.appendAnnotationFragment(trailing)
                }
            } else {
                if (currentText.isEmpty()) blockStartY = centerY(det)
                currentText.appendAnnotationFragment(text)
            }
        }
        saveCurrentBlock()

        return blocks
    }

    /**
     * Resolves implicit (untagged) text blocks by associating them with the nearest vertical canvas tag
     * of the most appropriate prefix.
     */
    private fun resolveImplicitBlocks(
        implicitBlocks: List<ParsedBlock>,
        canvasTags: List<Pair<String, DetectionResult>>,
        existingAnnotations: Map<String, String>
    ): Map<String, String> {
        val resolvedAnnotations = mutableMapOf<String, String>()

        val canvasTagsByPrefix = canvasTags
            .groupBy { (tag, _) -> tag.substringBefore('-') }
            .mapValues { (_, tags) -> tags.sortedBy { (_, det) -> centerY(det) } }

        val unresolvedTagsByPrefix = canvasTagsByPrefix
            .mapValues { (_, tags) ->
                tags.map { it.first }
                    .filter { tag -> tag !in existingAnnotations }
                    .sortedBy { tag -> WidgetTagParser.extractOrdinal(tag) ?: Int.MAX_VALUE }
                    .toMutableList()
            }.toMutableMap()

        for (block in implicitBlocks.sortedBy { it.centerY }) {
            val closestPrefix = unresolvedTagsByPrefix
                .filterValues { it.isNotEmpty() }
                .minByOrNull { (prefix, remainingTags) ->
                    val nearestTagY = canvasTagsByPrefix[prefix]
                        ?.firstOrNull { (tag, _) -> tag == remainingTags.firstOrNull() }
                        ?.second?.let { centerY(it) } ?: Float.MAX_VALUE
                    abs(nearestTagY - block.centerY)
                }?.key ?: continue

            val assignedTag = unresolvedTagsByPrefix[closestPrefix]?.removeFirstOrNull() ?: continue
            resolvedAnnotations[assignedTag] = block.annotationText
        }

        return resolvedAnnotations
    }

    private fun centerX(detection: DetectionResult): Float {
        return (detection.boundingBox.left + detection.boundingBox.right) / 2f
    }

    private fun centerY(detection: DetectionResult): Float {
        return (detection.boundingBox.top + detection.boundingBox.bottom) / 2f
    }

    private data class DetectionDistribution(
        val canvas: List<DetectionResult>,
        val leftMargin: List<DetectionResult>,
        val rightMargin: List<DetectionResult>
    )

    private data class GroupedBlocks(
        val explicitAnnotations: MutableMap<String, String> = mutableMapOf(),
        val implicitBlocks: MutableList<ParsedBlock> = mutableListOf()
    ) {
        fun addExplicitAnnotation(tag: String, text: String) {
            if (text.isBlank()) return
            explicitAnnotations.merge(tag, text) { existing, newText -> "$existing | $newText" }
        }
    }

    private data class ParsedBlock(
        val annotationText: String,
        val centerY: Float
    )

    private data class SourcedBlocks(
        val source: MetadataOcrSource?,
        val blocks: GroupedBlocks
    )

    private data class SourcedAnnotation(
        val text: String,
        val source: MetadataOcrSource?
    )

    private data class SourcedFragment(
        val text: String,
        val key: String?,
        val sourcePriority: Int
    )

    private data class SelectedFragment(
        val text: String,
        val sourcePriority: Int
    ) {
        fun isBetterThan(existing: SelectedFragment, key: String): Boolean {
            if (sourcePriority != existing.sourcePriority) return sourcePriority < existing.sourcePriority
            if (key !in DIMENSION_KEYS) return false
            return dimensionNoiseScore(text) < dimensionNoiseScore(existing.text)
        }
    }

    private val MetadataOcrSource?.priority: Int
        get() = when (this) {
            MetadataOcrSource.MARGIN_CROP -> 0
            null -> 1
            MetadataOcrSource.FULL_IMAGE -> 2
        }

    private fun StringBuilder.appendAnnotationFragment(text: String) {
        if (isNotBlank()) append(" | ")
        append(text)
    }

    private fun DetectionResult.isInvalidWidgetTag(): Boolean {
        return label == "widget_tag" && WidgetTagParser.extractTag(text) == null
    }

    private fun DetectionResult.isRenderableCrossBoundaryText(): Boolean {
        return label == "text" &&
            text.isNotBlank() &&
            !MetadataDetector.isCanvasMetadata(text) &&
            !WidgetTagParser.isTagSequence(text)
    }

    private val ATTRIBUTE_KEY_PREFIX = Regex("^\\s*([a-zA-Z_\\- ]+?)(?=\\s*[:;]|\\d)")
    private val DIMENSION_KEYS = AttributeKey.WIDTH.aliases.take(2).toSet() + AttributeKey.HEIGHT.aliases
    private val DIMENSION_OCR_NOISE = Regex("(?<=\\d)[DOIl](?=\\s*(?:dp|de|do)\\b)", RegexOption.IGNORE_CASE)
    private val UNLABELED_DIMENSION = Regex("^[0-9]+\\s*(?:dp|de|do)$", RegexOption.IGNORE_CASE)

    private fun dimensionNoiseScore(fragment: String): Int {
        return DIMENSION_OCR_NOISE.findAll(fragment).count()
    }
}
