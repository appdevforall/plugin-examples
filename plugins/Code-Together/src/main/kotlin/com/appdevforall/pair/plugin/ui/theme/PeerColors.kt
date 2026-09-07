package com.appdevforall.pair.plugin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val LightPeerPalette: List<Color> = listOf(
    Color(0xFF0F766E),
    Color(0xFFC2410C),
    Color(0xFF1E40AF),
    Color(0xFFA16207),
    Color(0xFF7E22CE),
)

private val DarkPeerPalette: List<Color> = listOf(
    Color(0xFF5EEAD4),
    Color(0xFFFB923C),
    Color(0xFF60A5FA),
    Color(0xFFFACC15),
    Color(0xFFC084FC),
)

@Composable
@ReadOnlyComposable
fun peerColorFor(colorIndex: Int): Color {
    val palette = if (isSystemInDarkTheme()) DarkPeerPalette else LightPeerPalette
    val safeIndex = ((colorIndex % palette.size) + palette.size) % palette.size
    return palette[safeIndex]
}
