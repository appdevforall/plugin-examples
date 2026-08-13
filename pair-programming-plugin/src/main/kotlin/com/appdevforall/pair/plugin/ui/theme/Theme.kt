package com.appdevforall.pair.plugin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun PluginTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useHostTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (useHostTheme) {
        rememberHostColorScheme(darkTheme)
    } else {
        if (darkTheme) PluginDarkColors else PluginLightColors
    }
    val extras = if (darkTheme) PluginDarkExtras else PluginLightExtras
    val textStyles = remember(colors, extras) { buildPluginTextStyles(colors, extras) }
    val typography = remember(textStyles) { buildPluginTypography(textStyles) }

    CompositionLocalProvider(
        LocalPluginExtraColors provides extras,
        LocalPluginDimens provides PluginDefaultDimens,
        LocalPluginTextStyles provides textStyles,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            content = content,
        )
    }
}
