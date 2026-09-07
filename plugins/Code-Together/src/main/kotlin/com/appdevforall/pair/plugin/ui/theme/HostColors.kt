package com.appdevforall.pair.plugin.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

@Composable
internal fun rememberHostColorScheme(darkTheme: Boolean): ColorScheme {
    val pluginContext = LocalContext.current
    val themeContext = remember(pluginContext) {
        findActivityContext(pluginContext) ?: pluginContext
    }
    return remember(themeContext, darkTheme) {
        buildHostColorScheme(themeContext, darkTheme)
    }
}

private fun buildHostColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    // Start from the Material3 defaults and override with the host IDE's theme attributes, so the
    // plugin's buttons and text take the host's exact colors (resolved from the host Activity theme).
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    fun attr(@AttrRes attrId: Int, fallback: Color): Color = resolveAttrColor(context, attrId, fallback)
    return base.copy(
        primary = attr(MaterialR.attr.colorPrimary, base.primary),
        onPrimary = attr(MaterialR.attr.colorOnPrimary, base.onPrimary),
        primaryContainer = attr(MaterialR.attr.colorPrimaryContainer, base.primaryContainer),
        onPrimaryContainer = attr(MaterialR.attr.colorOnPrimaryContainer, base.onPrimaryContainer),
        secondary = attr(MaterialR.attr.colorSecondary, base.secondary),
        onSecondary = attr(MaterialR.attr.colorOnSecondary, base.onSecondary),
        secondaryContainer = attr(MaterialR.attr.colorSecondaryContainer, base.secondaryContainer),
        onSecondaryContainer = attr(MaterialR.attr.colorOnSecondaryContainer, base.onSecondaryContainer),
        tertiary = attr(MaterialR.attr.colorTertiary, base.tertiary),
        onTertiary = attr(MaterialR.attr.colorOnTertiary, base.onTertiary),
        background = attr(android.R.attr.colorBackground, base.background),
        onBackground = attr(MaterialR.attr.colorOnBackground, base.onBackground),
        surface = attr(MaterialR.attr.colorSurface, base.surface),
        onSurface = attr(MaterialR.attr.colorOnSurface, base.onSurface),
        surfaceVariant = attr(MaterialR.attr.colorSurfaceVariant, base.surfaceVariant),
        onSurfaceVariant = attr(MaterialR.attr.colorOnSurfaceVariant, base.onSurfaceVariant),
        outline = attr(MaterialR.attr.colorOutline, base.outline),
        outlineVariant = attr(MaterialR.attr.colorOutlineVariant, base.outlineVariant),
        error = attr(MaterialR.attr.colorError, base.error),
        onError = attr(MaterialR.attr.colorOnError, base.onError),
        errorContainer = attr(MaterialR.attr.colorErrorContainer, base.errorContainer),
        onErrorContainer = attr(MaterialR.attr.colorOnErrorContainer, base.onErrorContainer),
        surfaceTint = Color.Transparent,
    )
}

private fun resolveAttrColor(context: Context, @AttrRes attrId: Int, fallback: Color): Color {
    val tv = TypedValue()
    if (!context.theme.resolveAttribute(attrId, tv, true)) return fallback
    return when {
        tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> Color(tv.data)
        tv.resourceId != 0 -> runCatching { Color(ContextCompat.getColor(context, tv.resourceId)) }.getOrElse { fallback }
        else -> fallback
    }
}

private fun findActivityContext(start: Context): Context? {
    var ctx: Context? = start
    while (ctx != null) {
        if (ctx is Activity) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}
