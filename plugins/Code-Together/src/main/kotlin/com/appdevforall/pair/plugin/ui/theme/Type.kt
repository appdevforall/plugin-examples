package com.appdevforall.pair.plugin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Immutable
data class PluginTextStyles(
    val display: TextStyle,
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val bodyMuted: TextStyle,
    val small: TextStyle,
    val label: TextStyle,
    val mono: TextStyle,
    val monoLarge: TextStyle,
    val monoHero: TextStyle,
)

internal fun buildPluginTextStyles(
    scheme: ColorScheme,
    extras: PluginExtraColors,
): PluginTextStyles {
    val display = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurface,
    )
    val title = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.015).em,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurface,
    )
    val subtitle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurface,
    )
    val body = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontFamily = FontFamily.SansSerif,
        color = scheme.onSurface,
    )
    val bodyMuted = body.copy(color = scheme.onSurfaceVariant)
    val small = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFamily = FontFamily.SansSerif,
        color = scheme.onSurfaceVariant,
    )
    val label = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurfaceVariant,
    )
    val mono = body.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
    )
    val monoLarge = title.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        letterSpacing = 0.em,
    )
    val monoHero = display.copy(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
        letterSpacing = (-0.01).em,
    )
    return PluginTextStyles(
        display = display,
        title = title,
        subtitle = subtitle,
        body = body,
        bodyMuted = bodyMuted,
        small = small,
        label = label,
        mono = mono,
        monoLarge = monoLarge,
        monoHero = monoHero,
    )
}

internal fun buildPluginTypography(styles: PluginTextStyles): Typography {
    // Typography must stay colorless: Material3 components provide per-slot content colors
    // (e.g. onPrimary inside a filled Button), and a color baked into the style overrides them.
    fun TextStyle.colorless() = copy(color = Color.Unspecified)
    return Typography(
        displayLarge = styles.display.colorless(),
        displayMedium = styles.display.colorless(),
        displaySmall = styles.display.colorless(),
        headlineLarge = styles.title.colorless(),
        headlineMedium = styles.title.colorless(),
        headlineSmall = styles.title.colorless(),
        titleLarge = styles.title.colorless(),
        titleMedium = styles.subtitle.colorless(),
        titleSmall = styles.subtitle.colorless(),
        bodyLarge = styles.body.colorless(),
        bodyMedium = styles.body.colorless(),
        bodySmall = styles.small.colorless(),
        // labelLarge drives button text — keep it at the Material3 default size so the plugin's
        // buttons match the host app's buttons rather than rendering oversized.
        labelLarge = styles.body.colorless().copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = styles.label.colorless(),
        labelSmall = styles.label.colorless(),
    )
}

private val FallbackTextStyles = buildPluginTextStyles(
    scheme = PluginLightColors,
    extras = PluginLightExtras,
)

val LocalPluginTextStyles = staticCompositionLocalOf { FallbackTextStyles }
