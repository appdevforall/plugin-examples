package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloToXmlConverterTest {

    @Test
    fun `screenshot metadata is matched to switch and image by canvas tags`() {
        val detections = listOf(
            detection("switch_off", "", 250f, 100f, 330f, 140f, region = SketchRegion.CANVAS),
            detection("image_placeholder", "", 260f, 260f, 460f, 360f, region = SketchRegion.CANVAS),
            detection("image_placeholder", "", 300f, 390f, 330f, 420f, region = SketchRegion.CANVAS),
            detection("text", "SW-1", 220f, 100f, 250f, 125f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 220f, 260f, 250f, 285f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1 P-1", 480f, 260f, 560f, 285f, isYolo = false, region = SketchRegion.CROSS_BOUNDARY),
            detection(
                "text",
                "SW-1 layout_width: layaut_width: 00dp id: Switeh 1 id: Switeh 1 checked:felse checked:false",
                20f,
                100f,
                190f,
                145f,
                isYolo = false,
                region = SketchRegion.LEFT_METADATA
            ),
            detection("text", "P-1 P-1", 700f, 255f, 760f, 280f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layaut-graity: start w/ap iol: m_View 1", 700f, 285f, 930f, 310f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "P-1 src: images", 700f, 315f, 830f, 340f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
        )

        val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
            detections = detections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasDetections,
            annotations = annotationMap,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""<androidx.appcompat.widget.SwitchCompat"""))
        assertTrue(xml.contains("""android:id="@+id/switch_1""""))
        assertTrue(xml.contains("""android:layout_width="100dp""""))
        assertTrue(xml.contains("""android:checked="false""""))
        assertFalse(xml.contains("""android:text="P-1 P-1""""))
        assertTrue(xml.contains("""android:id="@+id/im_view_1""""))
        assertTrue(xml.contains("""android:layout_gravity="start""""))
        assertTrue(xml.contains("""android:src="@drawable/images""""))
        assertFalse(xml.contains("""android:id="@+id/image_placeholder_0""""))
    }

    @Test
    fun `right margin image annotation preserves id gravity and src`() {
        val detections = listOf(
            detection("image_placeholder", "", 260f, 260f, 460f, 360f, region = SketchRegion.CANVAS),
            detection("text", "P-1", 220f, 260f, 250f, 285f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 700f, 255f, 760f, 280f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layout-width: 200dp", 700f, 285f, 860f, 310f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layout-height: wrap_content", 700f, 315f, 900f, 340f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "id: im_view_1", 700f, 345f, 840f, 370f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "src: images", 700f, 375f, 820f, 400f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layout-gravity: start", 700f, 405f, 900f, 430f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
        )

        val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
            detections = detections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )
        val parsed = FuzzyAttributeParser.parse(annotationMap["P-1"], "ImageView")
        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasDetections,
            annotations = annotationMap,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertEquals("im_view_1", parsed["android:id"])
        assertEquals("start", parsed["android:layout_gravity"])
        assertEquals("@drawable/images", parsed["android:src"])
        assertTrue(xml.contains("""android:id="@+id/im_view_1""""))
        assertFalse(xml.contains("""android:id="@+id/image_placeholder_0""""))
    }

    @Test
    fun `blank widget tag detections are ignored by parser and do not alter xml`() {
        val blankWidgetTag = detection(
            "widget_tag",
            "",
            564.047f,
            1342.8032f,
            616.454f,
            1382.0607f,
            region = SketchRegion.CANVAS
        ).copy(score = 0.35572785f)
        val baseDetections = listOf(
            detection("image_placeholder", "", 260f, 260f, 460f, 360f, region = SketchRegion.CANVAS),
            detection("text", "P-1", 220f, 260f, 250f, 285f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 700f, 255f, 760f, 280f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "id: im_view_1", 700f, 345f, 840f, 370f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "src: images", 700f, 375f, 820f, 400f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
        )

        val (canvasWithoutBlank, annotationsWithoutBlank) = MarginAnnotationParser.parse(
            detections = baseDetections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )
        val (canvasWithBlank, annotationsWithBlank) = MarginAnnotationParser.parse(
            detections = baseDetections + blankWidgetTag,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )
        val (xmlWithoutBlank, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasWithoutBlank,
            annotations = annotationsWithoutBlank,
            sourceImageWidth = 1000,
            sourceImageHeight = 1500,
            targetDpWidth = 1000,
            targetDpHeight = 1500,
            wrapInScroll = false
        )
        val (xmlWithBlank, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasWithBlank,
            annotations = annotationsWithBlank,
            sourceImageWidth = 1000,
            sourceImageHeight = 1500,
            targetDpWidth = 1000,
            targetDpHeight = 1500,
            wrapInScroll = false
        )

        assertFalse(canvasWithBlank.any { it.label == "widget_tag" && it.text.isBlank() })
        assertEquals(annotationsWithoutBlank, annotationsWithBlank)
        assertEquals(xmlWithoutBlank, xmlWithBlank)
        assertTrue(xmlWithBlank.contains("""android:id="@+id/im_view_1""""))
    }

    @Test
    fun `metadata region text is not treated as real widget text`() {
        val detections = listOf(
            detection("text", "P-1 layout_width: 200dp", 700f, 260f, 900f, 290f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("switch_off", "WiFi", 250f, 100f, 330f, 140f, region = SketchRegion.CANVAS)
        )

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = emptyMap(),
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""android:text="WiFi""""))
        assertFalse(xml.contains("layout_width: 200dp"))
    }

    @Test
    fun `cross boundary visible text remains renderable`() {
        val detections = listOf(
            detection("text", "Edge label", 190f, 260f, 230f, 285f, isYolo = false, region = SketchRegion.CROSS_BOUNDARY)
        )

        val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
            detections = detections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasDetections,
            annotations = annotationMap,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""android:text="Edge label""""))
    }

    @Test
    fun `small unmatched icons remain renderable widgets`() {
        val detections = listOf(
            detection("image_placeholder", "", 260f, 260f, 460f, 360f, region = SketchRegion.CANVAS),
            detection("icon", "", 300f, 390f, 330f, 420f, region = SketchRegion.CANVAS),
            detection("text", "P-1", 220f, 260f, 250f, 285f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 700f, 255f, 760f, 280f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "id: im_view_1", 700f, 345f, 840f, 370f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
        )

        val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
            detections = detections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasDetections,
            annotations = annotationMap,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""android:id="@+id/im_view_1""""))
        assertTrue(xml.contains("""android:id="@+id/icon_0""""))
    }

    @Test
    fun `small unmatched image placeholders away from annotated images remain renderable widgets`() {
        val detections = listOf(
            detection("image_placeholder", "", 260f, 260f, 460f, 360f, region = SketchRegion.CANVAS),
            detection("image_placeholder", "", 760f, 390f, 790f, 420f, region = SketchRegion.CANVAS),
            detection("text", "P-1", 220f, 260f, 250f, 285f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 700f, 255f, 760f, 280f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "id: im_view_1", 700f, 345f, 840f, 370f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
        )

        val (canvasDetections, annotationMap) = MarginAnnotationParser.parse(
            detections = detections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.60f
        )

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvasDetections,
            annotations = annotationMap,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""android:id="@+id/im_view_1""""))
        assertTrue(xml.contains("""android:id="@+id/image_placeholder_0""""))
    }

    private fun detection(
        label: String,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        isYolo: Boolean = true,
        region: SketchRegion? = null
    ): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(left, top, right, bottom).apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            },
            label = label,
            score = 0.99f,
            text = text,
            isYolo = isYolo,
            region = region
        )
    }
}
