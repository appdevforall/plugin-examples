package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    Text(
        text = text.uppercase(),
        style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = modifier.padding(
            horizontal = dimens.spaceLg,
            vertical = dimens.spaceSm,
        ),
    )
}

@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = LocalPluginDimens.current.liveDotSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@ThemePreviews
@Composable
private fun SectionLabelPreview() {
    PluginPreview {
        SectionLabel("Recent")
    }
}

@ThemePreviews
@Composable
private fun StatusDotPreview() {
    PluginPreview {
        Row {
            StatusDot(color = MaterialTheme.colorScheme.primary)
        }
    }
}
