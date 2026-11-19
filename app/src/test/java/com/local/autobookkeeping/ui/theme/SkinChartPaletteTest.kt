package com.local.autobookkeeping.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四套皮肤的统计色板契约：每套都要有完整的一套颜色、明确的切片处理和图例形状，
 * 而且彼此不能长成同一个样子。
 */
class SkinChartPaletteTest {

    @Test
    fun everySkinHasFullUniquePalette() {
        HeaderDecor.values().forEach { decor ->
            val palette = chartPaletteFor(decor)
            assertEquals("$decor 色板数量", 7, palette.colors.size)
            assertEquals("$decor 色板有重复色", palette.colors.size, palette.colors.distinct().size)
            assertTrue("$decor 缺口角度异常", palette.sliceGapDegrees in 0f..5f)
        }
    }

    @Test
    fun leadingColorDiffersBetweenSkins() {
        val leading = HeaderDecor.values().map { chartPaletteFor(it).colors.first() }
        assertEquals("四套皮肤的主色必须各不相同", 4, leading.distinct().size)
    }

    @Test
    fun colorAtWrapsBothDirections() {
        val palette = chartPaletteFor(HeaderDecor.OCEAN_WAVE)
        assertEquals(palette.colors[0], palette.colorAt(0))
        assertEquals(palette.colors[0], palette.colorAt(palette.colors.size))
        assertEquals(palette.colors.last(), palette.colorAt(-1))
    }

    @Test
    fun gapAndRimFollowSkinLanguage() {
        // 薄荷的切片连成一片，也不需要外框
        assertEquals(0f, chartPaletteFor(HeaderDecor.MINT_SPROUT).sliceGapDegrees)
        assertNull(chartPaletteFor(HeaderDecor.MINT_SPROUT).rimColor)

        // 小票像纸上剪开：留缝 + 一圈浅色边框
        val receipt = chartPaletteFor(HeaderDecor.RECEIPT_STUB)
        assertTrue(receipt.sliceGapDegrees > 0f)
        assertNotNull(receipt.rimColor)

        // 纸感留缝更大，并带一圈细墨线
        val paper = chartPaletteFor(HeaderDecor.PAPER_RULE)
        assertTrue(paper.sliceGapDegrees > receipt.sliceGapDegrees)
        assertNotNull(paper.rimColor)

        // 肥鱼留白边但不要外框，否则会跟光环叠在一起
        val fish = chartPaletteFor(HeaderDecor.OCEAN_WAVE)
        assertTrue(fish.sliceGapDegrees > 0f)
        assertNull(fish.rimColor)
    }

    @Test
    fun legendMarkIsUniquePerSkin() {
        val marks = HeaderDecor.values().map { chartPaletteFor(it).legendMark }
        assertEquals(4, marks.distinct().size)
    }
}
