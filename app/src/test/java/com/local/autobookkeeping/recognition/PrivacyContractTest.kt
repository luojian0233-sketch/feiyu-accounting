package com.local.autobookkeeping.recognition

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * 联网能力被有意收窄到「只看版本号」这一件事：只申请 INTERNET，
     * 不申请 ACCESS_NETWORK_STATE、不申请任何存储或读取类的权限。
     */
    @Test
    fun `manifest requests internet only for update checks`() {
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

        assertTrue("android.permission.INTERNET" in requested)
        assertFalse("android.permission.ACCESS_NETWORK_STATE" in requested)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in requested)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in requested)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in requested)
        assertFalse("android.permission.READ_SMS" in requested)
        assertFalse("android.permission.RECEIVE_SMS" in requested)
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in requested)
        assertFalse("android.permission.ACCESS_FINE_LOCATION" in requested)
    }

    /**
     * 放开 INTERNET 之后总得有人看着它别扩散：所有网络调用必须留在
     * `data/update` 里，账本、识别、备份这些包一个都不许碰 socket。
     */
    @Test
    fun `network calls live only in the update package`() {
        val mainRoot = projectRoot().resolve("app/src/main/java/com/local/autobookkeeping")
        val networkApis = setOf(
            "openConnection",
            "HttpURLConnection",
            "okhttp3",
            "java.net.Socket",
        )
        val offenders = Files.walk(mainRoot).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { path ->
                    !path.toString().replace('\\', '/').contains("data/update")
                }
                .flatMap { path ->
                    val source = Files.readString(path)
                    networkApis.stream()
                        .filter(source::contains)
                        .map { api -> "${mainRoot.relativize(path)} -> $api" }
                }
                .sorted()
                .toList()
        }

        assertEquals(emptyList<String>(), offenders)
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
