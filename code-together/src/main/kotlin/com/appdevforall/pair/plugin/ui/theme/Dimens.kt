package com.appdevforall.pair.plugin.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class PluginDimens(
    val spaceXs: Dp,
    val spaceSm: Dp,
    val spaceMd: Dp,
    val spaceLg: Dp,
    val spaceXl: Dp,
    val spaceXxl: Dp,
    val spaceXxxl: Dp,
    val radiusSm: Dp,
    val radiusMd: Dp,
    val radiusLg: Dp,
    val radiusXl: Dp,
    val borderHairline: Dp,
    val borderAccent: Dp,
    val touchTargetMin: Dp,
    val liveDotSize: Dp,
    val rowHeightMin: Dp,
)

internal val PluginDefaultDimens = PluginDimens(
    spaceXs = 4.dp,
    spaceSm = 8.dp,
    spaceMd = 12.dp,
    spaceLg = 16.dp,
    spaceXl = 24.dp,
    spaceXxl = 32.dp,
    spaceXxxl = 48.dp,
    radiusSm = 4.dp,
    radiusMd = 8.dp,
    radiusLg = 12.dp,
    radiusXl = 16.dp,
    borderHairline = 1.dp,
    borderAccent = 3.dp,
    touchTargetMin = 48.dp,
    liveDotSize = 10.dp,
    rowHeightMin = 56.dp,
)

val LocalPluginDimens = staticCompositionLocalOf { PluginDefaultDimens }
