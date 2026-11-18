package com.local.autobookkeeping.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * 标注徽章的帧资源契约：12 组（3 套皮肤 × 4 个页面）各有待机 16 帧 + 按下 16 帧。
 * 脚本改名、少数一帧或某页偷懒复用别页素材时运行时都不会报错，所以在这里钉死。
 */
class HeaderBadgeAssetsTest {
    private val root = File("src/main/assets/header_badge")

    @Test
    fun `every skin page pair ships idle and tap frames`() {
        assertEquals("三套皮肤 × 四页", 12, HeaderBadgeKind.entries.size)
        HeaderBadgeKind.entries.forEach { kind ->
            listOf("idle" to BADGE_IDLE_FRAMES, "tap" to BADGE_TAP_FRAME_MILLIS.size).forEach { (state, count) ->
                val dir = File(root, "${kind.assetDir}/$state")
                assertTrue("缺少 $dir", dir.isDirectory)
                val frames = dir.listFiles { file -> file.extension == "png" }?.sortedBy { it.name }.orEmpty()
                assertEquals("${kind.assetDir}/$state 帧数", count, frames.size)
                frames.forEachIndexed { index, file ->
                    assertEquals(String.format("%02d.png", index), file.name)
                }
            }
        }
    }

    @Test
    fun `badges keep one square canvas with transparency`() {
        HeaderBadgeKind.entries.forEach { kind ->
            listOf("idle", "tap").forEach { state ->
                val image = ImageIO.read(File(root, "${kind.assetDir}/$state/00.png"))
                assertEquals("${kind.assetDir}/$state 宽", 168, image.width)
                assertEquals("${kind.assetDir}/$state 高", 168, image.height)
                assertTrue("${kind.assetDir}/$state 需要透明背景", image.colorModel.hasAlpha())
            }
        }
    }

    @Test
    fun `idle loop actually moves`() {
        HeaderBadgeKind.entries.forEach { kind ->
            val first = File(root, "${kind.assetDir}/idle/00.png").readBytes().contentHashCode()
            val middle = File(root, "${kind.assetDir}/idle/07.png").readBytes().contentHashCode()
            assertNotEquals("${kind.assetDir} 待机帧全一样等于一张静态图", first, middle)
        }
    }

    @Test
    fun `tap playback is a burst not a copy of idle`() {
        HeaderBadgeKind.entries.forEach { kind ->
            val idle = File(root, "${kind.assetDir}/idle/00.png").readBytes().contentHashCode()
            val action = File(root, "${kind.assetDir}/tap/05.png").readBytes().contentHashCode()
            assertNotEquals("${kind.assetDir} 按下帧不能直接复用待机帧", idle, action)
        }
    }

    @Test
    fun `four pages within a skin are four different badges`() {
        listOf("mint", "receipt", "paper").forEach { skin ->
            val hashes = listOf("home", "ledger", "statistics", "settings").map { page ->
                File(root, "${skin}_$page/idle/00.png").readBytes().contentHashCode()
            }
            assertEquals("$skin 的四个页面得是四枚不同的徽章", 4, hashes.toSet().size)
        }
    }

    @Test
    fun `cadence matches the generator`() {
        assertEquals("待机 16 帧", 16, BADGE_IDLE_FRAMES)
        assertEquals("待机每帧 100ms", 100L, BADGE_IDLE_FRAME_MILLIS)
        assertEquals("按下 16 帧", 16, BADGE_TAP_FRAME_MILLIS.size)
        assertEquals("按下总时长 1370ms", 1370L, BADGE_TAP_FRAME_MILLIS.sum())
        assertTrue("动作段要比起手快", BADGE_TAP_FRAME_MILLIS.min() < BADGE_IDLE_FRAME_MILLIS)
    }

    @Test
    fun `fish skin falls back to its bubble instead of a badge`() {
        assertEquals(null, headerBadgeFor(HeaderDecor.OCEAN_WAVE, HeaderPage.HOME))
        listOf(
            HeaderDecor.MINT_SPROUT to "mint",
            HeaderDecor.RECEIPT_STUB to "receipt",
            HeaderDecor.PAPER_RULE to "paper",
        ).forEach { (decor, skin) ->
            HeaderPage.entries.forEach { page ->
                assertEquals(
                    "${skin}_${page.name.lowercase()}",
                    headerBadgeFor(decor, page)?.assetDir,
                )
            }
        }
    }
}
