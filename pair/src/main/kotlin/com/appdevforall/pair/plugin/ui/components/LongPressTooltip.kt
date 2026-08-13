package com.appdevforall.pair.plugin.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import com.appdevforall.pair.plugin.PairServiceLocator
import com.appdevforall.pair.plugin.PairTooltips
import com.itsaky.androidide.plugins.services.IdeTooltipService

fun Modifier.longPressTooltip(tag: String): Modifier = composed {
    val view = LocalView.current
    pointerInput(tag) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation(PointerEventPass.Initial)
                false
            } ?: true
            if (longPressed) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showPluginTooltip(view, tag)
                // Consume the rest of the gesture in the initial pass so the control
                // underneath cancels its press and does not also fire onClick on release.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        }
    }
}

private fun showPluginTooltip(anchor: View, tag: String) {
    val context = PairServiceLocator.get().pluginContext
    val service = context.services.get(IdeTooltipService::class.java)
    if (service == null) {
        context.logger.error("PairPlugin: IdeTooltipService unavailable, cannot show tooltip $tag")
        return
    }
    runCatching { service.showTooltip(anchor, PairTooltips.CATEGORY, tag) }
        .onFailure { context.logger.error("PairPlugin: showTooltip failed for $tag", it) }
}
