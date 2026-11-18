package com.local.autobookkeeping.ui.theme

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四种点击反馈的节奏契约：曲线两端必须回到原样、压扁幅度不能失控、
 * 播放按 40ms 一格推进（跟 `tools/gen_fish_reaction_preview.py` 的 STEP_MS 一致）。
 */
class FishReactionTest {
    @Test
    fun `every page gets its own reaction`() {
        val reactions = HeaderStickerKind.entries.map { it.reaction() }.toSet()
        assertEquals("四页要四种，不能有两页撞车", 4, reactions.size)
        assertEquals(
            setOf(
                FishReaction.STARS,
                FishReaction.COINS,
                FishReaction.STAMP,
                FishReaction.RING,
            ),
            reactions,
        )
    }

    @Test
    fun `durations stay in pixel-animation range`() {
        FishReaction.entries.forEach {
            assertTrue("$it 太短看不清", it.durationMillis >= 800L)
            assertTrue("$it 太长会拖慢操作", it.durationMillis <= 1_500L)
            assertTrue("$it 要有自己的名字", it.label.isNotBlank())
        }
    }

    @Test
    fun `squash rests at both ends`() {
        FishReaction.entries.forEach {
            assertEquals("$it 起始应是原样", 1f, reactionSquash(it, 0f), 0.001f)
            assertEquals("$it 结尾要接回待机", 1f, reactionSquash(it, 1f), 0.001f)
        }
    }

    @Test
    fun `squash never flips or inflates the sticker`() {
        FishReaction.entries.forEach { reaction ->
            for (step in 0..100) {
                val v = reactionSquash(reaction, step / 100f)
                assertTrue("$reaction t=${step / 100f} 压扁越界: $v", v in 0.81f..1.13f)
            }
        }
    }

    @Test
    fun `width compensates the squash so volume stays`() {
        assertEquals(1f, reactionWidthScale(1f), 0.001f)
        assertTrue("压扁时横向要撑开", reactionWidthScale(0.84f) > 1f)
        assertTrue("弹起时横向要收窄", reactionWidthScale(1.12f) < 1f)
    }

    @Test
    fun `playback walks the whole timeline at 40ms steps`() = runTest {
        val frames = mutableListOf<Float>()
        playFishReaction(FishReaction.STARS.durationMillis) { frames += it }

        assertEquals(27, frames.size)   // 1050ms / 40ms = 26 段，含首帧
        assertEquals(0f, frames.first(), 0.001f)
        assertEquals(1f, frames.last(), 0.001f)
        assertEquals(1_040L, testScheduler.currentTime)
    }
}
