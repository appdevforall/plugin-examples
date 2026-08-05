@file:JvmName("PluginToast")

package org.appdevforall.codeonthego.layouteditor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Shows a toast using a context whose resources include this plugin's package.
 *
 * utilcodex's ToastUtils resolves its own bundled layout through Utils.getApp() — the host
 * Application, whose Resources never have the plugin APK loaded — so it throws
 * Resources.NotFoundException ("No package ID <id> found") from inside a plugin.
 */
@JvmOverloads
fun showPluginToast(
    context: Context,
    message: CharSequence,
    duration: Int = Toast.LENGTH_SHORT,
) {
    if (Looper.myLooper() === Looper.getMainLooper()) {
        Toast.makeText(context, message, duration).show()
        return
    }
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context, message, duration).show()
    }
}
