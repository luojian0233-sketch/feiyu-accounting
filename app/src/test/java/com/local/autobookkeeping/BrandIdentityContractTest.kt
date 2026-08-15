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
    fun `launcher uses the approved Shizhang name and receipt mark`() {
        val manifest = parseXml(projectRoot().resolve("app/src/main/AndroidManifest.xml"))
        val strings = parseXml(projectRoot().resolve("app/src/main/res/values/strings.xml"))
        val application = manifest.getElementsByTagName("application").item(0).attributes

        assertEquals("@string/app_name", application.getNamedItemNS(ANDROID_NAMESPACE, "label")?.nodeValue)
        assertEquals("@mipmap/ic_launcher", application.getNamedItemNS(ANDROID_NAMESPACE, "icon")?.nodeValue)
        assertEquals("@mipmap/ic_launcher", application.getNamedItemNS(ANDROID_NAMESPACE, "roundIcon")?.nodeValue)
        assertEquals("拾账", stringValue(strings, "app_name"))
        assertTrue(Files.exists(projectRoot().resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml")))
        assertTrue(Files.exists(projectRoot().resolve("app/src/main/res/mipmap-anydpi/ic_launcher.xml")))

        val foreground = Files.readString(
            projectRoot().resolve("app/src/main/res/drawable/ic_launcher_foreground.xml"),
        ).lowercase()
        assertFalse("foreground must stay a graphic mark", foreground.contains("<text"))
        listOf("¥", "$", "pig", "coin", "calculator", "楼").forEach { forbidden ->
            assertFalse("foreground must not contain $forbidden", foreground.contains(forbidden))
        }
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
