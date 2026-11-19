package com.local.autobookkeeping.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 已安装应用的版本。
 *
 * [code] 是安装器唯一认的字段，决定能不能覆盖安装、有没有新版；
 * [name] 只用于界面展示和语义化比较。
 */
data class AppVersion(val name: String, val code: Int)

/** 远端最新一版。 */
data class LatestRelease(
    val versionName: String,
    val versionCode: Int?,
    val changelog: String,
    val downloadUrl: String,
    val releasePageUrl: String,
)

sealed interface UpdateCheckResult {
    /** 已是最新，或远端还没有发布过任何版本。 */
    data object UpToDate : UpdateCheckResult

    data class Available(val release: LatestRelease) : UpdateCheckResult
}

fun interface UpdateChecker {
    suspend fun check(current: AppVersion): Result<UpdateCheckResult>
}

/** 还没有在 gradle.properties 里填 appUpdateRepo。这不是错误，只是还没配好。 */
class UpdateSourceNotConfiguredException : IllegalStateException("尚未配置更新源")

/**
 * 走 GitHub Releases 的公开 API 读最新版本，不需要任何鉴权，也不需要单独维护
 * 一份 latest.json——Tag 就是版本号，APK 就是 release 资产，正文就是更新日志。
 *
 * 约束：只有公开仓库能匿名读；私有仓库需要 token，本项目不使用。
 */
class GithubUpdateChecker(
    private val repository: String,
    private val apiBase: String = "https://api.github.com",
) : UpdateChecker {

    override suspend fun check(current: AppVersion): Result<UpdateCheckResult> = try {
        if (repository.isBlank()) {
            Result.failure(UpdateSourceNotConfiguredException())
        } else {
            val payload = fetch("$apiBase/repos/$repository/releases/latest")
            val release = parseRelease(payload)
                ?: throw IOException("更新信息格式无法识别")
            Result.success(releaseCheckResult(release, current))
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub 对无 User-Agent 的请求直接 403。
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IOException("仓库或发布页不存在")
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $status")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val USER_AGENT = "Feiyu-Accounting-Android"
    }
}

/**
 * 只取需要的几个字段，用 [JsonElement] 手读而不是 @Serializable：
 * 项目没有启用 serialization 编译器插件，为六个字段引一个构建插件不划算。
 */
internal data class GithubRelease(
    val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val htmlUrl: String = "",
    val assets: List<GithubAsset> = emptyList(),
)

internal data class GithubAsset(
    val name: String = "",
    val downloadUrl: String = "",
)

internal fun parseRelease(payload: String): GithubRelease? = runCatching {
    val root = Json.parseToJsonElement(payload).jsonObject
    GithubRelease(
        tagName = root.stringOrEmpty("tag_name"),
        name = root.stringOrEmpty("name"),
        body = root.stringOrEmpty("body"),
        htmlUrl = root.stringOrEmpty("html_url"),
        assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
            val asset = element as? JsonObject ?: return@mapNotNull null
            val name = asset.stringOrEmpty("name")
            val url = asset.stringOrEmpty("browser_download_url")
            if (name.isBlank() && url.isBlank()) null else GithubAsset(name, url)
        },
    )
}.getOrNull()

private fun JsonObject.stringOrEmpty(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * 判断远端这一版是否比当前新。
 *
 * 优先用 versionCode（从资产文件名 `feiyu-1.2.0-vc3.apk` 里解析），它才是安装器
 * 认的字段；解析不到时才退回语义化比较 versionName。
 */
internal fun releaseCheckResult(
    release: GithubRelease,
    current: AppVersion,
): UpdateCheckResult {
    val versionName = normalizeVersionName(release.tagName.ifBlank { release.name })
    if (versionName.isBlank()) return UpdateCheckResult.UpToDate

    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    val versionCode = apk?.let { parseVersionCode(it.name) }
    val isNewer = if (versionCode != null) {
        versionCode > current.code
    } else {
        compareVersionNames(versionName, current.name) > 0
    }
    if (!isNewer) return UpdateCheckResult.UpToDate

    return UpdateCheckResult.Available(
        LatestRelease(
            versionName = versionName,
            versionCode = versionCode,
            changelog = release.body.trim(),
            downloadUrl = apk?.downloadUrl?.takeIf(String::isNotBlank) ?: release.htmlUrl,
            releasePageUrl = release.htmlUrl,
        ),
    )
}

/** `v1.2.0` → `1.2.0`；预发布后缀（`-beta.1`）直接丢弃，只比数字段。 */
internal fun normalizeVersionName(raw: String): String = raw.trim()
    .removePrefix("v")
    .removePrefix("V")
    .substringBefore('-')
    .substringBefore('+')
    .trim()

/** 逐段比较，缺的段按 0 处理，所以 `1.2` 与 `1.2.0` 相等。 */
internal fun compareVersionNames(left: String, right: String): Int {
    val leftParts = versionSegments(left)
    val rightParts = versionSegments(right)
    for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
        val a = leftParts.getOrElse(index) { 0 }
        val b = rightParts.getOrElse(index) { 0 }
        if (a != b) return if (a > b) 1 else -1
    }
    return 0
}

private fun versionSegments(value: String): List<Int> = normalizeVersionName(value)
    .split('.')
    .map { it.trim().takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

private val versionCodePattern = Regex("""-vc(\d+)(?:\.apk)?$""", RegexOption.IGNORE_CASE)

internal fun parseVersionCode(assetName: String): Int? = versionCodePattern
    .find(assetName.trim())
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()
