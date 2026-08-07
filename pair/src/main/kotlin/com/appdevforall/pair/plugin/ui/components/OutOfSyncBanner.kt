package com.appdevforall.pair.plugin.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun OutOfSyncBanner(
    visible: Boolean,
    canResync: Boolean,
    onResync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val shape = RoundedCornerShape(dimens.radiusMd)

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(200)),
        exit = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceSm)
                .height(40.dp)
                .border(BorderStroke(dimens.borderHairline, MaterialTheme.colorScheme.error), shape)
                .padding(horizontal = dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "OUT OF SYNC",
                style = styles.label.copy(color = MaterialTheme.colorScheme.error),
            )
            if (canResync) {
                PluginButtonText(
                    text = "RESYNC",
                    onClick = onResync,
                    contentColor = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun OutOfSyncBannerPreview() {
    PluginPreview {
        OutOfSyncBanner(
            visible = true,
            canResync = true,
            onResync = {},
        )
    }
}

@ThemePreviews
@Composable
private fun OutOfSyncBannerNoResyncPreview() {
    PluginPreview {
        OutOfSyncBanner(
            visible = true,
            canResync = false,
            onResync = {},
        )
    }
}
