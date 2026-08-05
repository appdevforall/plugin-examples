@file:JvmName("PluginToast")

package org.appdevforall.codeonthego.layouteditor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

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
