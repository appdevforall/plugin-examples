package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews

// These wrap the stock Material3 buttons with no shape/typography overrides, so they render exactly
// like the host IDE's buttons (Material3 pill shape, default text), themed by the host color scheme.

@Composable
fun PluginButtonFilled(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = ButtonDefaults.ContentPadding,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        ButtonContent(text, leadingIcon)
    }
}

@Composable
fun PluginButtonOutlined(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
    contentColor: Color = Color.Unspecified,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = if (contentColor != Color.Unspecified) {
            ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        ButtonContent(text, leadingIcon)
    }
}

@Composable
fun PluginButtonText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = if (contentColor != Color.Unspecified) {
            ButtonDefaults.textButtonColors(contentColor = contentColor)
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
        Text(text = text)
    }
}

@Composable
fun PluginButtonTonal(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        Text(text = text)
    }
}

@Composable
private fun ButtonContent(text: String, leadingIcon: Painter?) {
    if (leadingIcon != null) {
        Icon(painter = leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text = text)
}

@ThemePreviews
@Composable
private fun PluginButtonPreview() {
    PluginPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PluginButtonFilled(text = "HOST A SESSION", onClick = {})
            PluginButtonFilled(text = "HOST A SESSION", onClick = {}, enabled = false)
            PluginButtonOutlined(text = "JOIN A SESSION", onClick = {})
            PluginButtonText(text = "RESYNC", onClick = {})
            PluginButtonTonal(text = "CONNECT", onClick = {})
        }
    }
}
