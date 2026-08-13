package com.appdevforall.pair.plugin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightPrimary = Color(0xFF0F766E)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFCCFBF1)
private val LightOnPrimaryContainer = Color(0xFF042F2C)
private val LightSecondary = Color(0xFF404040)
private val LightOnSecondary = Color(0xFFFAFAF9)
private val LightSecondaryContainer = Color(0xFFF5F5F4)
private val LightOnSecondaryContainer = Color(0xFF0A0A0A)
private val LightSurface = Color(0xFFFAFAF9)
private val LightOnSurface = Color(0xFF0A0A0A)
private val LightSurfaceVariant = Color(0xFFF5F5F4)
private val LightOnSurfaceVariant = Color(0xFF525252)
private val LightSurfaceDim = Color(0xFFF0F0EE)
private val LightOutline = Color(0xFFA3A3A3)
private val LightOutlineVariant = Color(0xFFE5E5E5)
private val LightError = Color(0xFFB91C1C)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFEE2E2)
private val LightOnErrorContainer = Color(0xFF7F1D1D)
private val LightSuccess = Color(0xFF15803D)
private val LightSuccessContainer = Color(0xFFDCFCE7)
private val LightTextMuted = Color(0xFF737373)
private val LightTextSubtle = Color(0xFFA3A3A3)
private val LightRipple = Color(0x1F0A0A0A)

private val DarkPrimary = Color(0xFF5EEAD4)
private val DarkOnPrimary = Color(0xFF042F2C)
private val DarkPrimaryContainer = Color(0xFF134E48)
private val DarkOnPrimaryContainer = Color(0xFFCCFBF1)
private val DarkSecondary = Color(0xFFD4D4D4)
private val DarkOnSecondary = Color(0xFF0A0A0A)
private val DarkSecondaryContainer = Color(0xFF171717)
private val DarkOnSecondaryContainer = Color(0xFFFAFAFA)
private val DarkSurface = Color(0xFF0A0A0A)
private val DarkOnSurface = Color(0xFFFAFAFA)
private val DarkSurfaceVariant = Color(0xFF171717)
private val DarkOnSurfaceVariant = Color(0xFFA3A3A3)
private val DarkSurfaceDim = Color(0xFF000000)
private val DarkOutline = Color(0xFF525252)
private val DarkOutlineVariant = Color(0xFF262626)
private val DarkError = Color(0xFFFCA5A5)
private val DarkOnError = Color(0xFF5A1F1F)
private val DarkErrorContainer = Color(0xFF5A1F1F)
private val DarkOnErrorContainer = Color(0xFFFECACA)
private val DarkSuccess = Color(0xFF86EFAC)
private val DarkSuccessContainer = Color(0xFF14532D)
private val DarkTextMuted = Color(0xFFA3A3A3)
private val DarkTextSubtle = Color(0xFF525252)
private val DarkRipple = Color(0x1FFAFAFA)

internal val PluginLightColors: ColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightSecondary,
    onTertiary = LightOnSecondary,
    tertiaryContainer = LightSecondaryContainer,
    onTertiaryContainer = LightOnSecondaryContainer,
    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

internal val PluginDarkColors: ColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkSecondary,
    onTertiary = DarkOnSecondary,
    tertiaryContainer = DarkSecondaryContainer,
    onTertiaryContainer = DarkOnSecondaryContainer,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

@Immutable
data class PluginExtraColors(
    val accent: Color,
    val success: Color,
    val successContainer: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val surfaceDim: Color,
    val ripple: Color,
)

internal val PluginLightExtras = PluginExtraColors(
    accent = LightPrimary,
    success = LightSuccess,
    successContainer = LightSuccessContainer,
    textMuted = LightTextMuted,
    textSubtle = LightTextSubtle,
    surfaceDim = LightSurfaceDim,
    ripple = LightRipple,
)

internal val PluginDarkExtras = PluginExtraColors(
    accent = DarkPrimary,
    success = DarkSuccess,
    successContainer = DarkSuccessContainer,
    textMuted = DarkTextMuted,
    textSubtle = DarkTextSubtle,
    surfaceDim = DarkSurfaceDim,
    ripple = DarkRipple,
)

val LocalPluginExtraColors = staticCompositionLocalOf { PluginLightExtras }
