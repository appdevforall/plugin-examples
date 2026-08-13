package com.appdevforall.pair.plugin.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles
import kotlinx.coroutines.delay

@Composable
fun IpHero(
    address: String,
    port: Int,
    modifier: Modifier = Modifier,
    copyValue: String = "$address:$port",
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    val full = copyValue

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    copyToClipboard(context, full)
                    copied = true
                },
            )
            .semantics { contentDescription = "Tap to copy $full" }
            .padding(vertical = dimens.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "$address:$port",
            style = styles.monoLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        )
        Spacer(Modifier.height(dimens.spaceMd))
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                (fadeIn(tween(150)) togetherWith fadeOut(tween(150)))
            },
            label = "ip-hero-copy-indicator",
        ) { isCopied ->
            Text(
                text = if (isCopied) "COPIED" else "TAP TO COPY",
                style = styles.label.copy(
                    color = if (isCopied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
        }
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Pair address", value))
}

@ThemePreviews
@Composable
private fun IpHeroPreview() {
    PluginPreview {
        IpHero(address = "192.168.1.42", port = 7050)
    }
}
