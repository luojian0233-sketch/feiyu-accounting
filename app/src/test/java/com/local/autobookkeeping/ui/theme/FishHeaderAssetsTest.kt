package com.local.autobookkeeping.ui.theme

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 头卡贴纸的帧资源契约：四页各一张，脚本改名或少数一帧时运行时只会「少一帧」而不报错，
 * 所以这里钉死目录结构、帧数、画布尺寸与节奏。
 */
class FishHeaderAssetsTest {
    private val root = File("src/main/assets/fish_header")

    @Test
    fun `every page ships its own sticker`() {
        HeaderStickerKind.entries.forEach { kind ->
            val dir = File(root, kind.assetDir)
            assertTrue("缺少 $dir", dir.isDirectory)
            val frames = dir.listFiles { file -> file.extension == "png" }?.sortedBy { it.name }.orEmpty()
            assertEquals("${kind.assetDir} 帧数要跟节奏表对齐", kind.frameMillis.size, frames.size)
            frames.forEachIndexed { index, file ->
                assertEquals(String.format("%02d.png", index), file.name)
            }
        }
    }

    @Test
    fun `four stickers are four different captions`() {
        // 四张首帧不能是同一份资源：同一张图复制四遍就等于「每个都是好运来」
        val firstFrames = HeaderStickerKind.entries.map { kind ->
            File(root, "${kind.assetDir}/00.png").readBytes().contentHashCode()
        }
        assertEquals("四页应该有四种贴纸", 4, firstFrames.toSet().size)
    }

    @Test
    fun `header frames keep the portrait ratio and transparency`() {
        HeaderStickerKind.entries.forEach { kind ->
            val first = javax.imageio.ImageIO.read(File(root, "${kind.assetDir}/00.png"))
            assertEquals("${kind.assetDir} 高", 288, first.height)
            assertTrue("${kind.assetDir} 宽要跟原图比例一致（含底部字幕）", first.width in 225..255)
            assertTrue("${kind.assetDir} 需要透明背景", first.colorModel.hasAlpha())
            // 气泡内圈只有 47dp，贴纸宽高比失控会骑到环上
            assertTrue("${kind.assetDir} 别比气泡内径更胖", first.width.toFloat() / first.height <= 0.90f)
        }
    }

    @Test
    fun `sticker cadence matches the source gifs`() {
        listOf(
            HeaderStickerKind.HOME,
            HeaderStickerKind.LEDGER,
            HeaderStickerKind.STATISTICS,
        ).forEach { kind ->
            assertEquals("${kind.assetDir} 24 帧", 24, kind.frameMillis.size)
            assertTrue("${kind.assetDir} 每帧 80ms", kind.frameMillis.all { it == 80L })
        }
        assertEquals("设置这张 19 帧", 19, HeaderStickerKind.SETTINGS.frameMillis.size)
        assertEquals("总时长 1920ms（原 GIF）", 1920L, HeaderStickerKind.SETTINGS.frameMillis.sum())
        assertTrue("素材里有停两拍的长帧", HeaderStickerKind.SETTINGS.frameMillis.any { it == 160L })
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

    @Test
    fun `uneven beats keep their own pace`() = runTest {
        // 设置那张有几个 160ms 的长拍：一圈 320ms（80+160+80），不能按 80×3 算
        val beats = longArrayOf(80, 160, 80)
        val played = mutableListOf<Int>()
        val job = launch { loopStickerFrames(beats) { played += it } }

        testScheduler.advanceTimeBy(239)
        assertEquals("长帧还没走完，第三帧不该出来", listOf(0, 1), played.toList())
        testScheduler.advanceTimeBy(82)
        assertEquals("走满一圈接回第一帧", listOf(0, 1, 2, 0), played.toList())
        job.cancel()
    }
}
