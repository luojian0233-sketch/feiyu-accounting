package com.local.autobookkeeping.data.update

import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 下载器对着本机起的一个 HTTP 桩测：不联网，也能验证分块写入、进度回调和错误码处理。
 */
class HttpApkDownloaderTest {

    @Test
    fun `downloads the whole body and reports monotonic progress`() = runTest {
        val payload = ByteArray(220_000) { index -> (index % 251).toByte() }
        serveOnce(status = "200 OK", body = payload).use { server ->
            val directory = Files.createTempDirectory("feiyu-dl").toFile()
            val progress = mutableListOf<Int>()

            val file = HttpApkDownloader(directory)
                .download("http://127.0.0.1:${server.port}/feiyu.apk") { progress += it }
                .getOrThrow()

            assertEquals(payload.size.toLong(), file.length())
            assertEquals(file.length(), Files.readAllBytes(file.toPath()).size.toLong())
            assertEquals(100, progress.last())
            assertTrue(
                progress.zipWithNext().all { (previous, next) -> next >= previous },
                "进度必须单调递增，实际是 $progress",
            )
        }
    }

    @Test
    fun `server error surfaces as a failed download`() = runTest {
        serveOnce(status = "404 Not Found", body = ByteArray(0)).use { server ->
            val directory = Files.createTempDirectory("feiyu-dl").toFile()

            val result = HttpApkDownloader(directory)
                .download("http://127.0.0.1:${server.port}/missing.apk") { }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("404"))
        }
    }

    /** 起一个只应答一次的最小 HTTP 服务，返回端口；use 结束时关闭。 */
    private fun serveOnce(status: String, body: ByteArray): AutoCloseableServer {
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true, name = "http-stub") {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val head = buildString {
                        append("HTTP/1.1 ").append(status).append("\r\n")
                        append("Content-Length: ").append(body.size).append("\r\n")
                        append("Content-Type: application/octet-stream\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(head.toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        return AutoCloseableServer(server.localPort) {
            server.close()
            worker.join(2_000)
        }
    }

    private class AutoCloseableServer(
        val port: Int,
        private val onClose: () -> Unit,
    ) : AutoCloseable {
        override fun close() = onClose()
    }
}
