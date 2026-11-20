package com.local.autobookkeeping.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖安装前的三道闸：包名、签名、版本。这里只测判定，不碰文件系统与 PackageManager。
 */
class LocalApkUpdateTest {

    private val installed = ApkIdentity(
        packageName = "com.local.autobookkeeping",
        versionName = "1.0",
        versionCode = 1,
        signerSha256 = "abc123",
    )

    @Test
    fun `same package newer version is installable`() {
        val verdict = judgeLocalApk(
            candidate = installed.copy(versionName = "1.1", versionCode = 2),
            installed = installed,
        ) as LocalUpdateVerdict.Installable

        assertEquals("1.1", verdict.versionName)
        assertEquals(2, verdict.versionCode)
        assertEquals(false, verdict.replacesSameVersion)
    }

    @Test
    fun `same version is allowed so a broken install can be repaired`() {
        val verdict = judgeLocalApk(candidate = installed.copy(), installed = installed)
            as LocalUpdateVerdict.Installable

        assertTrue(verdict.replacesSameVersion)
    }

    @Test
    fun `older version is rejected as a downgrade`() {
        val verdict = judgeLocalApk(
            candidate = installed.copy(versionName = "0.9", versionCode = 0),
            installed = installed.copy(versionName = "1.1", versionCode = 2),
        )

        verdict as LocalUpdateVerdict.Rejected
        assertEquals(LocalUpdateRejection.Downgrade, verdict.reason)
        assertTrue(verdict.message.contains("更旧"))
    }

    @Test
    fun `another app's apk is rejected before anything else`() {
        val verdict = judgeLocalApk(
            candidate = ApkIdentity("com.example.other", "9.9", 99, "zzz"),
            installed = installed,
        )

        verdict as LocalUpdateVerdict.Rejected
        assertEquals(LocalUpdateRejection.ForeignPackage, verdict.reason)
        assertTrue(verdict.message.contains("com.example.other"))
    }

    @Test
    fun `different signing certificate is rejected`() {
        val verdict = judgeLocalApk(
            candidate = installed.copy(versionCode = 2, signerSha256 = "deadbeef"),
            installed = installed,
        )

        verdict as LocalUpdateVerdict.Rejected
        assertEquals(LocalUpdateRejection.SignatureMismatch, verdict.reason)
    }

    @Test
    fun `signature comparison ignores case`() {
        val verdict = judgeLocalApk(
            candidate = installed.copy(versionCode = 2, signerSha256 = "ABC123"),
            installed = installed,
        )

        assertTrue(verdict is LocalUpdateVerdict.Installable)
    }

    @Test
    fun `missing signature on either side falls through to the system installer`() {
        val verdict = judgeLocalApk(
            candidate = installed.copy(versionCode = 2, signerSha256 = null),
            installed = installed,
        )

        assertTrue(verdict is LocalUpdateVerdict.Installable)
    }
}
