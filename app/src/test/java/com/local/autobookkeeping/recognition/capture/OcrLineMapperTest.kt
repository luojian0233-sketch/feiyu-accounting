package com.local.autobookkeeping.recognition.capture

import com.local.autobookkeeping.recognition.TextBounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrLineMapperTest {
    @Test
    fun `mapper preserves lines and bounds without retaining full image text`() {
        val result = OcrLineMapper.map(
            listOf(
                RawOcrLine("中国电信", 20, 40, 180, 80),
                RawOcrLine("¥1.00", 300, 200, 480, 280),
            ),
        )

        assertEquals(2, result.size)
        assertEquals("中国电信", result[0].text)
        assertEquals(TextBounds(300, 200, 480, 280), result[1].bounds)
    }
}
