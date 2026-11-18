package com.local.autobookkeeping.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底栏贴纸是构建期生成到 assets 里的逐帧 PNG，文件名和帧数必须跟
 * [FishStickerKind] 对得上，否则运行时会静默少帧。
 */
class FishNavAssetsTest {
    private val root = File("src/main/assets/fish_nav")

    @Test
    fun `every sticker has a folder with all frames`() {
        FishStickerKind.entries.forEach { kind ->
            val dir = File(root, kind.directory)
            assertTrue("缺少 ${kind.directory}", dir.isDirectory)
            val frames = dir.listFiles { file -> file.extension == "png" }?.sortedBy { it.name }.orEmpty()
            assertEquals("${kind.directory} 帧数不对", kind.frameCount, frames.size)
            frames.forEachIndexed { index, file ->
                assertEquals(String.format("%02d.png", index), file.name)
            }
        }
    }

    @Test
    fun `sticker frames keep the pixel art canvas`() {
        FishStickerKind.entries.forEach { kind ->
            val first = File(File(root, kind.directory), "00.png")
            val image = javax.imageio.ImageIO.read(first)
            assertEquals("${kind.directory} 宽", 120, image.width)
            assertEquals("${kind.directory} 高", 120, image.height)
            assertTrue("${kind.directory} 需要透明背景", image.colorModel.hasAlpha())
        }
    }
}
