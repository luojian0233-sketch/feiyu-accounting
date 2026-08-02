package com.local.autobookkeeping.recognition.capture

import android.graphics.Bitmap
import com.local.autobookkeeping.recognition.ObservedTextElement

fun interface LocalTextRecognizer {
    suspend fun recognize(bitmap: Bitmap): List<ObservedTextElement>
}
