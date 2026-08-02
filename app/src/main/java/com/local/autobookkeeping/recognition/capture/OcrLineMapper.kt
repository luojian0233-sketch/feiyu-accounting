package com.local.autobookkeeping.recognition.capture

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.TextBounds

internal data class RawOcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal object OcrLineMapper {
    fun map(lines: List<RawOcrLine>): List<ObservedTextElement> = lines.map { line ->
        ObservedTextElement(
            text = line.text,
            bounds = TextBounds(
                left = line.left,
                top = line.top,
                right = line.right,
                bottom = line.bottom,
            ),
        )
    }
}
