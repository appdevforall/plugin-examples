package com.appdevforall.pair.plugin.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

@Composable
fun ConnectingLine(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "connecting-line")
    val progress by infinite.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "connecting-line-progress",
    )
    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(150)),
        modifier = modifier,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
        ) {
            drawRect(color = trackColor, size = Size(size.width, size.height))
            val segmentWidth = size.width * 0.3f
            val x = progress * size.width
            drawRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(segmentWidth, size.height),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun ConnectingLinePreview() {
    PluginPreview {
        ConnectingLine(visible = true)
    }
}
