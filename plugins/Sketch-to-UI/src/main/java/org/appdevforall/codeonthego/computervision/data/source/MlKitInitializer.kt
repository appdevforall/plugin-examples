package org.appdevforall.codeonthego.computervision.data.source

import android.content.Context
import android.content.ContextWrapper
import com.google.firebase.components.ComponentRegistrar
import com.google.mlkit.common.internal.CommonComponentRegistrar
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.internal.VisionCommonRegistrar
import com.google.mlkit.vision.text.internal.TextRegistrar
import java.util.concurrent.atomic.AtomicBoolean

object MlKitInitializer {
    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context): Context {
        val mlKitContext = MlKitContextWrapper(context)
        if (initialized.get()) {
            return mlKitContext
        }

        MlKitContext.initializeIfNeeded(mlKitContext, registrars())
        initialized.set(true)
        return mlKitContext
    }

    private fun registrars(): List<ComponentRegistrar> {
        return listOf(
            TextRegistrar(),
            VisionCommonRegistrar(),
            CommonComponentRegistrar()
        )
    }

    private class MlKitContextWrapper(context: Context) : ContextWrapper(context) {
        override fun getApplicationContext(): Context {
            return this
        }
    }
}
