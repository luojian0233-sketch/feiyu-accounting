package com.local.autobookkeeping.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把远端 APK 拉到本地缓存。
 *
 * 只负责下载：校验和调起安装器由 [LocalApkInstaller] 做。抽成接口是为了让
 * ViewModel 能拿假实现跑状态机测试，不必真的联网。
 */
fun interface ApkDownloader {
    /** [onProgress] 拿 0..100 的百分比；总长度未知时调用方自己显示不确定进度。 */
    suspend fun download(url: String, onProgress: (Int) -> Unit): Result<File>
}

/**
 * 走 GitHub Release 资产下载。GitHub 会 302 到 objects.githubusercontent.com，
 * 必须允许跟随跳转，并且要带 User-Agent，否则直接被拒。
 *
 * 下载到 `cache/update/feiyu-download.apk`：Cache 目录不需要任何存储权限，
 * 系统空间紧张时也能回收，装完下一轮下载会先清掉旧文件。
 */
class HttpApkDownloader(private val cacheDir: File) : ApkDownloader {

    override suspend fun download(
        url: String,
        onProgress: (Int) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(cacheDir, "update")
            directory.listFiles()?.forEach { it.delete() }
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("无法创建缓存目录")
            }
            val target = File(directory, "feiyu-download.apk")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("HTTP $status")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                if (target.length() == 0L) throw IOException("下载内容为空")
                target
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
        const val USER_AGENT = "Feiyu-Accounting-Android"
    }
}
