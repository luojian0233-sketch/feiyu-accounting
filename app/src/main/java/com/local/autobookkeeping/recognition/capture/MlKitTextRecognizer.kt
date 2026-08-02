package com.local.autobookkeeping.recognition.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.local.autobookkeeping.recognition.ObservedTextElement
import java.io.Closeable
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitTextRecognizer : LocalTextRecognizer, Closeable {
    private val client = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )

    override suspend fun recognize(bitmap: Bitmap): List<ObservedTextElement> =
        suspendCancellableCoroutine { continuation ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val mappedLines = runCatching {
                        OcrLineMapper.map(
                            result.textBlocks.flatMap { block ->
                                block.lines.mapNotNull { line ->
                                    line.boundingBox?.let { bounds ->
                                        RawOcrLine(
                                            text = line.text,
                                            left = bounds.left,
                                            top = bounds.top,
                                            right = bounds.right,
                                            bottom = bounds.bottom,
                                        )
                                    }
                                }
                            },
                        )
                    }

                    mappedLines.fold(
                        onSuccess = continuation::resume,
                        onFailure = continuation::resumeWithException,
                    )
                }
                .addOnFailureListener(continuation::resumeWithException)
                .addOnCanceledListener {
                    continuation.cancel(CancellationException("ML Kit text recognition was cancelled"))
                }
        }

    override fun close() = client.close()
}
