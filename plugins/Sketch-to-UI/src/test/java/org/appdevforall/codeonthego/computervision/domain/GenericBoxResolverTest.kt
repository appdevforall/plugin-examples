package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class GenericBoxResolverTest {

    private val resolver = GenericBoxResolver()

    @Test
    fun `generic box with high confidence dropdown symbol near right edge becomes dropdown`() {
        val result = resolver.resolve(
            listOf(
                detection("generic_box", left = 100f, top = 100f, right = 300f, bottom = 150f),
                detection("dropdown_symbol", left = 265f, top = 115f, right = 285f, bottom = 135f, score = 0.95f)
            )
        )

        assertEquals(listOf("dropdown"), result.map { it.label })
    }

    @Test
    fun `generic box with high confidence dropdown symbol near left edge becomes dropdown`() {
        val result = resolver.resolve(
            listOf(
                detection("generic_box", left = 100f, top = 100f, right = 300f, bottom = 150f),
                detection("dropdown_symbol", left = 115f, top = 115f, right = 135f, bottom = 135f, score = 0.95f)
            )
        )

        assertEquals(listOf("dropdown"), result.map { it.label })
    }

    @Test
    fun `generic box with low confidence dropdown symbol near left edge remains text entry box`() {
        val result = resolver.resolve(
            listOf(
                detection("generic_box", left = 100f, top = 100f, right = 300f, bottom = 150f),
                detection("dropdown_symbol", left = 115f, top = 115f, right = 135f, bottom = 135f, score = 0.33f)
            )
        )

        assertEquals(listOf("text_entry_box"), result.map { it.label })
    }

    @Test
    fun `generic box with no dropdown symbol remains text entry box`() {
        val result = resolver.resolve(
            listOf(
                detection("generic_box", left = 100f, top = 100f, right = 300f, bottom = 150f)
            )
        )

        assertEquals(listOf("text_entry_box"), result.map { it.label })
    }

    private fun detection(
        label: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Float = 0.99f
    ): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(left, top, right, bottom),
            label = label,
            score = score
        )
    }
}
