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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles
import com.appdevforall.pair.plugin.util.NetUtil
import kotlinx.coroutines.delay

@Composable
fun InviteCard(
    address: String,
    port: Int,
    token: String?,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val invite = if (token.isNullOrEmpty()) "ws://$address:$port" else NetUtil.buildInvite(address, port, token)

    PluginCard(modifier = modifier.widthIn(max = 360.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Address + passcode (read out / typed on the other device).
            IpHero(address = address, port = port, copyValue = invite)
            if (!token.isNullOrEmpty()) {
                Spacer(Modifier.height(dimens.spaceMd))
                PasscodeRow(token = token)
            }
        }
    }
}

@Composable
private fun PasscodeRow(
    token: String,
    modifier: Modifier = Modifier,
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    copyToClipboard(context, token)
                    copied = true
                },
            )
            .semantics { contentDescription = "Tap to copy passcode" }
            .padding(vertical = dimens.spaceSm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "passcode-copy-indicator",
        ) { isCopied ->
            Text(
                text = if (isCopied) "COPIED" else "PASSCODE",
                style = styles.label.copy(
                    color = if (isCopied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
        }
        Spacer(Modifier.height(dimens.spaceXs))
        Text(
            text = token.chunked(4).joinToString(" "),
            style = styles.monoLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            textAlign = TextAlign.Center,
        )
    }
}

private fun copyToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Pair passcode", value))
}

@ThemePreviews
@Composable
private fun InviteCardPreview() {
    PluginPreview {
        InviteCard(
            address = "192.168.1.42",
            port = 7050,
            token = "4821",
        )
    }
}
