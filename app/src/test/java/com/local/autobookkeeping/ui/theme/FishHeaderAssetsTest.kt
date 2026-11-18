package com.local.autobookkeeping.ui.theme

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 头卡贴纸的帧资源契约：脚本改名或少数一帧时，运行时只会「少一帧」而不报错，
 * 所以这里钉死目录结构、帧数与画布尺寸。
 */
class FishHeaderAssetsTest {
    private val dir = File("src/main/assets/fish_header")

    @Test
    fun `header sticker ships every frame`() {
        assertTrue("缺少 $dir", dir.isDirectory)
        val frames = dir.listFiles { file -> file.extension == "png" }?.sortedBy { it.name }.orEmpty()
        assertEquals(HEADER_STICKER_FRAMES, frames.size)
        frames.forEachIndexed { index, file ->
            assertEquals(String.format("%02d.png", index), file.name)
        }
    }

    @Test
    fun `header frames keep the portrait ratio and transparency`() {
        val first = javax.imageio.ImageIO.read(File(dir, "00.png"))
        assertEquals("高", 288, first.height)
        assertTrue("宽要按原图 216:271 的比例（含「好运来」）", first.width in 230..240)
        assertTrue("需要透明背景", first.colorModel.hasAlpha())
    }

    @Test
    fun `card says the sticker frame cadence`() {
        assertEquals(80L, HEADER_STICKER_FRAME_MILLIS)
    }
}

/** 头卡贴纸要一直循环，不能像底栏那样停在第一帧。 */
class HeaderStickerLoopTest {
    @Test
    fun `sticker wraps around instead of stopping`() = runTest {
        val played = mutableListOf<Int>()
        val job = launch { loopStickerFrames(24, 80L) { played += it } }

        // 走满三圈再多 1ms，第四圈的第一帧应该已经画出来了
        testScheduler.advanceTimeBy(24 * 80L * 3 + 1)
        job.cancel()

        val oneLoop = (0 until 24).toList()
        assertEquals(List(3) { oneLoop }.flatten(), played.take(72))
        assertEquals("转完一圈要接上，不能停在最后一帧", 0, played[72])
    }
}
