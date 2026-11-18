package com.local.autobookkeeping.ui.theme

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 气泡素材契约：两套动画的帧数、画布、透明底，以及「戳破」的逐帧节奏。
 * 资源由 `tools/gen_fish_bubble.py` 从用户给的两个 GIF 切出来，改了脚本这里要跟着动。
 */
class FishBubbleAssetsTest {
    private val dir = File("src/main/assets/fish_bubble")

    @Test
    fun `idle and tap ship every frame`() {
        listOf("idle" to BUBBLE_IDLE_FRAMES, "tap" to BUBBLE_TAP_FRAME_MILLIS.size).forEach { (set, count) ->
            val frames = File(dir, set).listFiles { file -> file.extension == "png" }
                ?.sortedBy { it.name }.orEmpty()
            assertEquals("$set 帧数", count, frames.size)
            frames.forEachIndexed { index, file ->
                assertEquals(String.format("%02d.png", index), file.name)
            }
        }
    }

    @Test
    fun `frames share one square canvas with transparency`() {
        listOf("idle/00.png", "tap/05.png", "tap/16.png").forEach { path ->
            val bitmap = javax.imageio.ImageIO.read(File(dir, path))
            assertEquals("$path 宽高要一致（两套动画同一取景，戳破时不能跳位）", 370, bitmap.width)
            assertEquals("$path", 370, bitmap.height)
            assertTrue("$path 需要透明背景", bitmap.colorModel.hasAlpha())
        }
    }

    @Test
    fun `tap cadence matches the source gif`() {
        assertEquals(16, BUBBLE_IDLE_FRAMES)
        assertEquals(120L, BUBBLE_IDLE_FRAME_MILLIS)
        assertEquals("20 帧", 20, BUBBLE_TAP_FRAME_MILLIS.size)
        assertEquals("总时长 1740ms（原 GIF）", 1740L, BUBBLE_TAP_FRAME_MILLIS.sum())
        assertTrue("破碎段要快过默认循环", BUBBLE_TAP_FRAME_MILLIS.min() < BUBBLE_IDLE_FRAME_MILLIS)
    }

    @Test
    fun `sticker hides while the bubble is broken`() {
        assertTrue("第 4 帧开始裂", 4 in BUBBLE_TAP_STICKER_HIDDEN)
        assertTrue("第 15 帧小泡冒头就把贴纸放回来", 15 in BUBBLE_TAP_STICKER_HIDDEN)
        assertTrue(3 !in BUBBLE_TAP_STICKER_HIDDEN)
        assertTrue(16 !in BUBBLE_TAP_STICKER_HIDDEN)
    }
}

/** 戳破整段：20 帧按各自时长走完、顺序不乱，走完能接回默认循环。 */
class BubbleBurstTest {
    @Test
    fun `burst walks every frame at its own pace`() = runTest {
        val played = mutableListOf<Int>()
        playBubbleBurst(BUBBLE_TAP_FRAME_MILLIS) { played += it }

        assertEquals((0 until 20).toList(), played)
        assertEquals("总时长要跟原 GIF 一致", 1740L, testScheduler.currentTime)
    }

    @Test
    fun `idle loop resumes after the burst`() = runTest {
        val order = mutableListOf<String>()
        val burst = launch {
            playBubbleBurst(BUBBLE_TAP_FRAME_MILLIS) { order += "tap$it" }
            order += "burst-done"
        }
        burst.invokeOnCompletion { }
        testScheduler.advanceTimeBy(1741)
        val idle = launch { loopStickerFrames(BUBBLE_IDLE_FRAMES, BUBBLE_IDLE_FRAME_MILLIS) { order += "idle$it" } }
        testScheduler.advanceTimeBy(BUBBLE_IDLE_FRAME_MILLIS * 2 + 1)
        idle.cancel()

        assertEquals("tap19", order[19])
        assertEquals("burst-done", order[20])
        assertEquals("idle0", order[21])
        assertEquals("idle1", order[22])
    }
}
