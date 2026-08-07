package com.appdevforall.pair.plugin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
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
    return Typography(
        displayLarge = styles.display,
        displayMedium = styles.display,
        displaySmall = styles.display,
        headlineLarge = styles.title,
        headlineMedium = styles.title,
        headlineSmall = styles.title,
        titleLarge = styles.title,
        titleMedium = styles.subtitle,
        titleSmall = styles.subtitle,
        bodyLarge = styles.body,
        bodyMedium = styles.body,
        bodySmall = styles.small,
        // labelLarge drives button text — keep it at the Material3 default size so the plugin's
        // buttons match the host app's buttons rather than rendering oversized.
        labelLarge = styles.body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = styles.label,
        labelSmall = styles.label,
    )
}

private val FallbackTextStyles = buildPluginTextStyles(
    scheme = PluginLightColors,
    extras = PluginLightExtras,
)

val LocalPluginTextStyles = staticCompositionLocalOf { FallbackTextStyles }
