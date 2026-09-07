package org.appdevforall.codeonthego.computervision.data.source

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class OcrSource(context: Context) {

    private val safeContext: Context = MlKitInitializer.initialize(context)

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeText(bitmap: Bitmap): Result<List<Text.TextBlock>> =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                val safeBitmap = bitmap.safeCopyForMlKit()
                InputImage.fromBitmap(safeBitmap, 0)
            }.onSuccess { inputImage ->
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        continuation.resume(Result.success(visionText.textBlocks))
                    }
                    .addOnFailureListener { exception ->
                        continuation.resume(Result.failure(exception))
                    }
            }.onFailure { exception ->
                continuation.resume(Result.failure(exception))
        }
    }

    private fun Bitmap.safeCopyForMlKit(): Bitmap {
        check(!isRecycled) { "Bitmap is recycled" }
        check(width > 0 && height > 0) { "Bitmap has invalid size ${width}x${height}" }
        return if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
    }
}
