package com.local.autobookkeeping.recognition

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivacyContractTest {
    @Test
    fun `recognition implementation does not persist screenshots notification bodies or OCR text`() {
        val recognitionRoot = projectRoot().resolve(
            "app/src/main/java/com/local/autobookkeeping/recognition",
        )
        val forbiddenPersistenceApis = setOf(
            "FileOutputStream",
            "openFileOutput",
            "MediaStore.Images",
            "Bitmap.compress",
            "Files.write",
        )
        val offenders = Files.walk(recognitionRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .flatMap { path ->
                    val source = Files.readString(path)
                    forbiddenPersistenceApis.stream()
                        .filter(source::contains)
                        .map { api -> "${recognitionRoot.relativize(path)} -> $api" }
                }
                .sorted()
                .toList()
        }

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `manifest requests no internet broad storage or overlay permission`() {
        val manifestPath = projectRoot().resolve("app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestPath.toFile())
        val permissions = document.getElementsByTagName("uses-permission")
        val requested = (0 until permissions.length).mapNotNull { index ->
            permissions.item(index).takeUnless { permission ->
                permission.attributes
                    .getNamedItemNS(TOOLS_NAMESPACE, "node")
                    ?.nodeValue == "remove"
            }?.attributes?.getNamedItemNS(ANDROID_NAMESPACE, "name")?.nodeValue
        }.toSet()

        assertFalse("android.permission.INTERNET" in requested)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in requested)
    }

    @Test
    fun `accessibility service cannot perform gestures`() {
        val servicePath = projectRoot().resolve(
            "app/src/main/res/xml/payment_page_accessibility_service.xml",
        )
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(servicePath.toFile())
        val root = document.documentElement

        assertFalse(
            root.getAttributeNS(ANDROID_NAMESPACE, "canPerformGestures").toBoolean(),
        )
    }

    private fun projectRoot(): Path {
        val current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        return generateSequence(current) { it.parent }
            .first { Files.exists(it.resolve("app/src/main/AndroidManifest.xml")) }
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val TOOLS_NAMESPACE = "http://schemas.android.com/tools"
    }
}
