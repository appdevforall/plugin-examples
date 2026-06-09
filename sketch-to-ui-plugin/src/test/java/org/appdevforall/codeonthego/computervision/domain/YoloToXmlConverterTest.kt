package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.MetadataOcrSource
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        val parsed = FuzzyAttributeParser.parse(annotationMap.getValue("P-1"), "ImageView")
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

    @Test
    fun `overlapping password text entry boxes collapse to one edit text with parsed metadata`() {
        val detections = listOf(
            detection("text_entry_box", "Email", 250f, 100f, 550f, 150f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 250f, 200f, 550f, 252f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 260f, 205f, 540f, 248f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 248f, 198f, 552f, 254f, region = SketchRegion.CANVAS),
            detection("text", "T-1", 215f, 112f, 245f, 134f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "T-2", 215f, 212f, 245f, 234f, isYolo = false, region = SketchRegion.CANVAS)
        )
        val annotations = mapOf(
            "T-1" to "layout_width: 200dp | layout_height: 52dp | id: email | inputType: textEmailAddress",
            "T-2" to "layoutwidth | layoutwidthi20O0dp | layoutheight: | layoutheight30 | t | extassword | textPassword | credential | ieredeetal"
        )
        val resolvedAnnotations = MetadataAnnotationRecovery.resolve(annotations)

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = resolvedAnnotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertEquals(2, Regex("<EditText\\b").findAll(xml).count())
        assertFalse(xml.contains("""android:id="@+id/credential""""))
        assertTrue(xml.contains("""android:layout_width="200dp""""))
        assertTrue(xml.contains("""android:layout_height="52dp""""))
        assertTrue(xml.contains("""android:inputType="textPassword""""))
        assertTrue(xml.contains("""android:hint="Password""""))
        assertFalse(xml.contains("""android:text="credential"""))
        assertFalse(xml.contains("""ieredeetal"""))
        assertFalse(xml.contains("""android:orientation="horizontal""""))
    }

    @Test
    fun `compact degraded T-2 metadata recovers password edit text from same prefix evidence`() {
        val detections = listOf(
            detection("text_entry_box", "Email", 250f, 100f, 550f, 152f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 250f, 200f, 550f, 203f, region = SketchRegion.CANVAS),
            detection("text", "T-1", 215f, 112f, 245f, 134f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "T-2", 215f, 212f, 245f, 234f, isYolo = false, region = SketchRegion.CANVAS)
        )
        val annotations = mapOf(
            "T-1" to "layoutwidth:200dp | layoutheight:52dp | hint:Email | id:user_email",
            "T-2" to "layoutwidthi200dp | layoutheight3 | vextPassword | credental | layoutwidthi20O0dp | layoutheight30 | extassword | credential"
        )
        val resolvedAnnotations = MetadataAnnotationRecovery.resolve(annotations)

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = resolvedAnnotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        val editTexts = xmlBlocks(xml, "EditText")
        val secondEditText = editTexts[1]
        assertAttribute(secondEditText, "android:layout_width", "200dp")
        assertAttribute(secondEditText, "android:layout_height", "52dp")
        assertAttribute(secondEditText, "android:hint", "Password")
        assertAttribute(secondEditText, "android:inputType", "textPassword")
        assertFalse(secondEditText.contains("""android:layout_height="3dp""""))
    }

    @Test
    fun `same prefix dimension recovery does not cross widget groups`() {
        val annotations = MetadataAnnotationRecovery.resolve(
            mapOf(
                "B-1" to "layoutheight:52dp",
                "T-2" to "layoutheight3 | vextPassword | credental"
            )
        )

        val parsed = FuzzyAttributeParser.parse(annotations.getValue("T-2"), "EditText")

        assertNull(parsed["android:layout_height"])
    }

    @Test
    fun `same prefix dimension recovery does not override valid explicit value`() {
        val annotations = MetadataAnnotationRecovery.resolve(
            mapOf(
                "T-1" to "layoutheight:52dp",
                "T-2" to "layout_height:30dp | vextPassword | credential | credential_input"
            )
        )

        val parsed = FuzzyAttributeParser.parse(annotations.getValue("T-2"), "EditText")

        assertEquals("30dp", parsed["android:layout_height"])
    }

    @Test
    fun `nearby distinct text entry boxes are not collapsed`() {
        val detections = listOf(
            detection("text_entry_box", "First", 250f, 100f, 450f, 152f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Last", 470f, 100f, 670f, 152f, region = SketchRegion.CANVAS),
            detection("text", "T-1", 215f, 112f, 245f, 134f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "T-2", 435f, 112f, 465f, 134f, isYolo = false, region = SketchRegion.CANVAS)
        )
        val annotations = mapOf(
            "T-1" to "id: first_name | layout_width: 120dp | layout_height: 52dp",
            "T-2" to "id: last_name | layout_width: 120dp | layout_height: 52dp"
        )
        val resolvedAnnotations = MetadataAnnotationRecovery.resolve(annotations)

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = resolvedAnnotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertEquals(2, Regex("<EditText\\b").findAll(xml).count())
        assertTrue(xml.contains("""android:id="@+id/first_name""""))
        assertTrue(xml.contains("""android:id="@+id/last_name""""))
    }

    @Test
    fun `transitive overlapping text entry boxes collapse to one edit text`() {
        val detections = listOf(
            detection("text_entry_box", "Password", 250f, 200f, 350f, 252f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 280f, 200f, 380f, 252f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 310f, 200f, 410f, 252f, region = SketchRegion.CANVAS),
            detection("text", "T-1", 215f, 212f, 245f, 234f, isYolo = false, region = SketchRegion.CANVAS)
        )
        val annotations = mapOf(
            "T-1" to "id: credential | layout_width: 200dp | layout_height: 52dp | inputType: textPassword"
        )
        val resolvedAnnotations = MetadataAnnotationRecovery.resolve(annotations)

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = resolvedAnnotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertEquals(1, Regex("<EditText\\b").findAll(xml).count())
        assertTrue(xml.contains("""android:id="@+id/credential""""))
        assertFalse(xml.contains("""android:orientation="horizontal""""))
    }

    @Test
    fun `margin crop metadata takes priority without reordering by score`() {
        val detections = listOf(
            detection("text", "B-1", 250f, 200f, 280f, 225f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "B-1", 20f, 200f, 60f, 225f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(score = 0.70f, metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "layoutwidth:15Ddp", 20f, 230f, 180f, 255f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(score = 0.70f, metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "layoutwidth:150dp", 20f, 260f, 180f, 285f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(score = 0.70f, metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "B-1", 20f, 100f, 60f, 125f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(score = 0.99f, metadataSource = MetadataOcrSource.FULL_IMAGE),
            detection("text", "layoutwidth:120dp", 20f, 130f, 180f, 155f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(score = 0.99f, metadataSource = MetadataOcrSource.FULL_IMAGE)
        )

        val (_, annotations) = MarginAnnotationParser.parse(
            detections = detections,
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.80f
        )
        val parsed = FuzzyAttributeParser.parse(annotations.getValue("B-1"), "Button")

        assertEquals("150dp", parsed["android:layout_width"])
    }

    @Test
    fun `duplicate OCR sources recover ids only from clean candidate for same widget`() {
        val detections = listOf(
            detection("text", "T-1", 250f, 100f, 280f, 125f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "SW-1", 250f, 200f, 290f, 225f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "T-1", 20f, 100f, 60f, 125f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "id:credential", 20f, 130f, 170f, 155f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "SW-1", 20f, 200f, 70f, 225f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "id:remember", 20f, 230f, 170f, 255f, isYolo = false, region = SketchRegion.LEFT_METADATA)
                .copy(metadataSource = MetadataOcrSource.MARGIN_CROP),
            detection("text", "T-1", 700f, 100f, 740f, 125f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
                .copy(metadataSource = MetadataOcrSource.FULL_IMAGE),
            detection("text", "idl:eredential", 700f, 130f, 850f, 155f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
                .copy(metadataSource = MetadataOcrSource.FULL_IMAGE),
            detection("text", "SW-1", 700f, 200f, 750f, 225f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
                .copy(metadataSource = MetadataOcrSource.FULL_IMAGE),
            detection("text", "id:remenber", 700f, 230f, 850f, 255f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
                .copy(metadataSource = MetadataOcrSource.FULL_IMAGE)
        )

        val (_, annotations) = MarginAnnotationParser.parse(detections, 1000, 0.20f, 0.80f)

        assertEquals("credential", FuzzyAttributeParser.parse(annotations.getValue("T-1"), "EditText")["android:id"])
        assertEquals("remember", FuzzyAttributeParser.parse(annotations.getValue("SW-1"), "Switch")["android:id"])
    }

    @Test
    fun `score differences do not move OCR lines into another annotation block`() {
        val detections = listOf(
            detection("text", "B-1", 250f, 100f, 280f, 125f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "B-2", 250f, 200f, 280f, 225f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "B-1", 20f, 100f, 60f, 125f, isYolo = false, region = SketchRegion.LEFT_METADATA).copy(score = 0.50f),
            detection("text", "width:100dp", 20f, 130f, 170f, 155f, isYolo = false, region = SketchRegion.LEFT_METADATA).copy(score = 0.99f),
            detection("text", "B-2", 20f, 200f, 60f, 225f, isYolo = false, region = SketchRegion.LEFT_METADATA).copy(score = 0.99f),
            detection("text", "width:200dp", 20f, 230f, 170f, 255f, isYolo = false, region = SketchRegion.LEFT_METADATA).copy(score = 0.50f)
        )

        val (_, annotations) = MarginAnnotationParser.parse(detections, 1000, 0.20f, 0.80f)

        assertEquals("100dp", FuzzyAttributeParser.parse(annotations.getValue("B-1"), "Button")["android:layout_width"])
        assertEquals("200dp", FuzzyAttributeParser.parse(annotations.getValue("B-2"), "Button")["android:layout_width"])
    }

    @Test
    fun `recoverable noisy metadata generates corrected widget xml`() {
        val detections = listOf(
            detection("text_entry_box", "Password", 250f, 100f, 550f, 152f, region = SketchRegion.CANVAS),
            detection("switch_off", "", 250f, 220f, 400f, 272f, region = SketchRegion.CANVAS),
            detection("button", "Submit", 250f, 340f, 450f, 390f, region = SketchRegion.CANVAS),
            detection("image_placeholder", "", 250f, 460f, 314f, 524f, region = SketchRegion.CANVAS),
            detection("text", "T-1", 210f, 110f, 240f, 135f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "SW-1", 200f, 230f, 240f, 255f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "B-1", 210f, 350f, 240f, 375f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 210f, 470f, 240f, 495f, isYolo = false, region = SketchRegion.CANVAS)
        )
        val annotations = mapOf(
            "T-1" to "layoutwidth:200dp | layoutheiqht:52dp | id:credential | idl:eredential | inputtype:textPassword",
            "SW-1" to "layout_widthi100dp | layoutheiqht:52dp | id:remember | id:remenber | layoutgravity:centerhorizontal",
            "B-1" to "layoutwidth:150dp | layoutwidth:15Ddp | height:50de | background:red",
            "P-1" to "height:64dp 64dp id:imglogo | src:logo"
        )
        val resolvedAnnotations = MetadataAnnotationRecovery.resolve(annotations)

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = detections,
            annotations = resolvedAnnotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""android:id="@+id/credential""""))
        assertTrue(xml.contains("""android:inputType="textPassword""""))
        assertTrue(xml.contains("""android:id="@+id/remember""""))
        assertTrue(xml.contains("""android:layout_gravity="center_horizontal""""))
        assertTrue(xml.contains("""android:layout_width="150dp""""))
        assertTrue(xml.contains("""android:layout_height="50dp""""))
        assertTrue(xml.contains("""app:backgroundTint="#FF0000""""))
        assertTrue(xml.contains("""android:id="@+id/img_logo""""))
        assertEquals(2, Regex("""android:layout_width="64dp"|android:layout_height="64dp"""").findAll(xml).count())
        assertTrue(xml.contains("""android:src="@drawable/logo""""))
    }

    @Test
    fun `similar login sketch keeps same block metadata recovery consistent`() {
        val detections = listOf(
            detection("text_entry_box", "Email", 250f, 100f, 550f, 152f, region = SketchRegion.CANVAS),
            detection("text_entry_box", "Password", 250f, 200f, 550f, 252f, region = SketchRegion.CANVAS),
            detection("switch_off", "Rememberme", 250f, 310f, 400f, 362f, region = SketchRegion.CANVAS),
            detection("image_placeholder", "", 250f, 430f, 314f, 494f, region = SketchRegion.CANVAS),
            detection("text", "T-1", 210f, 110f, 240f, 135f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "T-2", 210f, 210f, 240f, 235f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "SW-1", 200f, 320f, 240f, 345f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 210f, 440f, 240f, 465f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "T-1", 20f, 100f, 60f, 125f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "layoutwidth:200dp", 20f, 130f, 180f, 155f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "layoutheight:52dp", 20f, 160f, 180f, 185f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "hint:Email", 20f, 190f, 160f, 215f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "icl useremail", 20f, 220f, 180f, 245f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "id:user_email", 20f, 250f, 180f, 275f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "T-2", 20f, 300f, 60f, 325f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "layoutwidthi200dp", 20f, 330f, 180f, 355f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "layoutheight3", 20f, 360f, 160f, 385f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "vextPassword", 20f, 390f, 160f, 415f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "credential", 20f, 420f, 160f, 445f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "SW-1", 700f, 310f, 750f, 335f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layout_widthi100dp", 700f, 340f, 880f, 365f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layoutheight:52dp", 700f, 370f, 880f, 395f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "id:remenber", 700f, 400f, 850f, 425f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "idi remember", 700f, 430f, 850f, 455f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "idiremember", 700f, 460f, 850f, 485f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "layoutgravity:centerhorizontal", 700f, 490f, 950f, 515f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "P-1", 700f, 550f, 750f, 575f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "height:64dp", 700f, 580f, 850f, 605f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "64dp", 700f, 610f, 760f, 635f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "id:imglogo", 700f, 640f, 850f, 665f, isYolo = false, region = SketchRegion.RIGHT_METADATA),
            detection("text", "src:logo.png", 700f, 670f, 850f, 695f, isYolo = false, region = SketchRegion.RIGHT_METADATA)
        )

        val (canvas, annotations) = MarginAnnotationParser.parse(detections, 1000, 0.20f, 0.80f)
        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvas,
            annotations = annotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        val editTexts = xmlBlocks(xml, "EditText")
        val firstEditText = editTexts[0]
        val secondEditText = editTexts[1]
        val switch = xmlBlocks(xml, "androidx.appcompat.widget.SwitchCompat").single()
        val image = xmlBlocks(xml, "ImageView").single()

        assertAttribute(firstEditText, "android:id", "@+id/user_email")
        assertAttribute(firstEditText, "android:hint", "Email")
        assertFalse(firstEditText.contains("Email icl useremail"))
        assertAttribute(secondEditText, "android:layout_width", "200dp")
        assertAttribute(secondEditText, "android:layout_height", "52dp")
        assertAttribute(secondEditText, "android:hint", "Password")
        assertAttribute(secondEditText, "android:inputType", "textPassword")
        assertAttribute(switch, "android:id", "@+id/remember")
        assertFalse(switch.contains("""android:id="@+id/switch_0""""))
        assertAttribute(image, "android:id", "@+id/img_logo")
        assertAttribute(image, "android:layout_width", "64dp")
        assertAttribute(image, "android:layout_height", "64dp")
        assertAttribute(image, "android:src", "@drawable/logo")
    }

    @Test
    fun `separate adjacent image dimension OCR line recovers square xml`() {
        val detections = listOf(
            detection("image_placeholder", "", 250f, 460f, 314f, 524f, region = SketchRegion.CANVAS),
            detection("text", "P-1", 210f, 470f, 240f, 495f, isYolo = false, region = SketchRegion.CANVAS),
            detection("text", "P-1", 20f, 460f, 60f, 485f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "height:64dp", 20f, 490f, 160f, 515f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "64dp", 20f, 520f, 80f, 545f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "id:imglogo", 20f, 550f, 150f, 575f, isYolo = false, region = SketchRegion.LEFT_METADATA),
            detection("text", "src:logo.png", 20f, 580f, 160f, 605f, isYolo = false, region = SketchRegion.LEFT_METADATA)
        )
        val (canvas, annotations) = MarginAnnotationParser.parse(detections, 1000, 0.20f, 0.80f)

        val (xml, _) = YoloToXmlConverter.generateXmlLayout(
            detections = canvas,
            annotations = annotations,
            sourceImageWidth = 1000,
            sourceImageHeight = 1000,
            targetDpWidth = 1000,
            targetDpHeight = 1000,
            wrapInScroll = false
        )

        assertTrue(xml.contains("""android:id="@+id/img_logo""""))
        assertTrue(xml.contains("""android:layout_width="64dp""""))
        assertTrue(xml.contains("""android:layout_height="64dp""""))
        assertTrue(xml.contains("""android:src="@drawable/logo""""))
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

    private fun xmlBlocks(xml: String, tag: String): List<String> {
        return Regex("<${Regex.escape(tag)}\\b[\\s\\S]*?/>").findAll(xml).map { it.value }.toList()
    }

    private fun assertAttribute(block: String, name: String, value: String) {
        assertTrue(block.contains("""$name="$value""""))
    }
}
