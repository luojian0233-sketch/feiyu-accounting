package com.local.autobookkeeping

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandIdentityContractTest {
    @Test
    fun `launcher uses the approved Feiyu name and whale girl mark`() {
        val root = projectRoot()
        val manifest = parseXml(root.resolve("app/src/main/AndroidManifest.xml"))
        val strings = parseXml(root.resolve("app/src/main/res/values/strings.xml"))
        val application = manifest.getElementsByTagName("application").item(0).attributes

        assertEquals("@string/app_name", application.getNamedItemNS(ANDROID_NAMESPACE, "label")?.nodeValue)
        assertEquals("@mipmap/ic_launcher", application.getNamedItemNS(ANDROID_NAMESPACE, "icon")?.nodeValue)
        assertEquals("@mipmap/ic_launcher", application.getNamedItemNS(ANDROID_NAMESPACE, "roundIcon")?.nodeValue)
        assertEquals("肥鱼记账", stringValue(strings, "app_name"))

        // 自适应图标：暖沙色渐变底 + 位图前景（不再是矢量小鱼）
        val adaptive = Files.readString(root.resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"))
        assertTrue(adaptive.contains("@drawable/ic_launcher_background"))
        assertTrue(adaptive.contains("@mipmap/ic_launcher_foreground"))
        assertFalse(
            "old vector foreground must be gone",
            Files.exists(root.resolve("app/src/main/res/drawable/ic_launcher_foreground.xml")),
        )
    }

    @Test
    fun `every launcher density ships both adaptive and legacy bitmaps`() {
        val root = projectRoot().resolve("app/src/main/res")
        val scales = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)

        scales.forEach { (density, scale) ->
            val foreground = root.resolve("mipmap-$density/ic_launcher_foreground.png")
            val legacy = root.resolve("mipmap-$density/ic_launcher.png")
            assertTrue("missing $foreground", Files.exists(foreground))
            assertTrue("missing $legacy", Files.exists(legacy))

            val (fw, fh) = pngSize(foreground)
            assertEquals("adaptive layer must be 108dp wide", (108 * scale).toInt(), fw)
            assertEquals(fw, fh)

            val (lw, lh) = pngSize(legacy)
            assertEquals("legacy icon must be 48dp wide", (48 * scale).toInt(), lw)
            assertEquals(lw, lh)
        }
    }

    @Test
    fun `mascot art ships as drawables and stays decorative`() {
        val root = projectRoot().resolve("app/src/main/res/drawable-nodpi")
        listOf("whale_face.png", "whale_mascot.png").forEach { name ->
            val file = root.resolve(name)
            assertTrue("missing $name", Files.exists(file))
            val (w, h) = pngSize(file)
            assertEquals(w, h)
            assertTrue("$name should be at least 256px", w >= 256)
        }

        // 装饰图形不得进入无障碍树：吉祥物 Image 必须 contentDescription = null
        val elements = Files.readString(
            projectRoot().resolve("app/src/main/java/com/local/autobookkeeping/ui/theme/WhaleElements.kt"),
        )
        assertFalse(elements.contains("contentDescription = \""))
    }

    private fun pngSize(path: Path): Pair<Int, Int> {
        val bytes = Files.readAllBytes(path)
        require(bytes.size > 24) { "$path is too small to be a PNG" }
        require(bytes[1].toInt() == 'P'.code && bytes[2].toInt() == 'N'.code && bytes[3].toInt() == 'G'.code) {
            "$path is not a PNG"
        }
        fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }

    private fun parseXml(path: Path) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(path.toFile())

    private fun stringValue(document: org.w3c.dom.Document, name: String): String? =
        (0 until document.getElementsByTagName("string").length)
            .map { document.getElementsByTagName("string").item(it) }
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == name }
            ?.textContent

    private fun projectRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("app/src/main/AndroidManifest.xml")) }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
