package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens

@Composable
fun PluginCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dimens = LocalPluginDimens.current
    val shape = RoundedCornerShape(dimens.radiusLg)
    val border = BorderStroke(dimens.borderHairline, MaterialTheme.colorScheme.outlineVariant)
    val colors: CardColors = CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surface,
    )
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) { content() }
    } else {
        OutlinedCard(
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
        ) { content() }
    }
}

@ThemePreviews
@Composable
private fun PluginCardPreview() {
    PluginPreview {
        PluginCard {
            Text(
                text = "Card content",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PluginCardClickablePreview() {
    PluginPreview {
        PluginCard(onClick = {}) {
            Text(
                text = "Clickable card content",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
