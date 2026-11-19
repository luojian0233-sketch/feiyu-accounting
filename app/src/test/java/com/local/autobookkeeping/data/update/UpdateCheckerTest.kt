package com.local.autobookkeeping.data.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UpdateCheckerTest {

    @Test
    fun `version segments compare numerically instead of as text`() {
        assertEquals(1, compareVersionNames("1.10.0", "1.9.0"))
        assertEquals(-1, compareVersionNames("1.2", "1.2.1"))
        assertEquals(0, compareVersionNames("1.2", "1.2.0"))
    }

    @Test
    fun `tag prefix and prerelease suffix are ignored`() {
        assertEquals("1.2.0", normalizeVersionName("v1.2.0"))
        assertEquals("1.2.0", normalizeVersionName("V1.2.0-beta.1"))
        assertEquals("1.2.0", normalizeVersionName(" 1.2.0+build5 "))
    }

    @Test
    fun `version code is parsed from the apk asset name`() {
        assertEquals(3, parseVersionCode("feiyu-1.2.0-vc3.apk"))
        assertEquals(12, parseVersionCode("feiyu-1.2.0-vc12.APK"))
        assertNull(parseVersionCode("feiyu-1.2.0.apk"))
    }

    /** versionCode 才是安装器认的字段，名字长得一样但 code 更大也算新版本。 */
    @Test
    fun `larger version code wins even when the version name looks identical`() {
        val release = GithubRelease(
            tagName = "v1.2.0",
            htmlUrl = "https://example.com/release",
            assets = listOf(
                GithubAsset(
                    name = "feiyu-1.2.0-vc4.apk",
                    downloadUrl = "https://example.com/app.apk",
                ),
            ),
        )

        val available = assertInstanceOf(
            UpdateCheckResult.Available::class.java,
            releaseCheckResult(release, AppVersion(name = "1.2.0", code = 3)),
        )
        assertEquals("1.2.0", available.release.versionName)
        assertEquals(4, available.release.versionCode)
        assertEquals("https://example.com/app.apk", available.release.downloadUrl)
    }

    @Test
    fun `same version reports up to date`() {
        val release = GithubRelease(
            tagName = "v1.2.0",
            assets = listOf(GithubAsset("feiyu-1.2.0-vc3.apk", "https://example.com/app.apk")),
        )

        assertEquals(
            UpdateCheckResult.UpToDate,
            releaseCheckResult(release, AppVersion(name = "1.2.0", code = 3)),
        )
    }

    /** 还没传 APK 时至少把发布页给出去，不要让「去下载」点不开。 */
    @Test
    fun `missing apk asset falls back to the release page`() {
        val release = GithubRelease(tagName = "v1.3.0", htmlUrl = "https://example.com/release")

        val available = assertInstanceOf(
            UpdateCheckResult.Available::class.java,
            releaseCheckResult(release, AppVersion(name = "1.0", code = 1)),
        )
        assertEquals("https://example.com/release", available.release.downloadUrl)
        assertNull(available.release.versionCode)
    }

    @Test
    fun `malformed json yields null instead of throwing`() {
        assertNull(parseRelease("{not json"))
        assertNull(parseRelease(""))
    }

    @Test
    fun `unknown json fields are ignored`() {
        val payload = """{"tag_name":"v1.3.0","extra_field":123,"assets":[]}"""

        assertEquals("v1.3.0", parseRelease(payload)?.tagName)
    }

    @Test
    fun `release without a tag is treated as up to date`() {
        assertEquals(
            UpdateCheckResult.UpToDate,
            releaseCheckResult(GithubRelease(), AppVersion(name = "1.0", code = 1)),
        )
    }
}
