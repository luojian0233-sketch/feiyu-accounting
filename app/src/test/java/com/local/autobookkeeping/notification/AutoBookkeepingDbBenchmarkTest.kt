package com.local.autobookkeeping.notification

import androidx.room.Room
import com.local.autobookkeeping.data.NotificationSourceSettings
import com.local.autobookkeeping.data.RoomLedgerRepository
import com.local.autobookkeeping.data.db.AppDatabase
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 含真实 SQLite 的端到端基准：通知 → 解析 → 去重查询 → 事务写入。
 *
 * 用内存版 Room（底层仍是真 SQLite），所以这里包含了解析之外的落库成本，
 * 是[AutoBookkeepingBenchmarkTest] 的补充，用来回答“一条通知到真正记账要多久”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoBookkeepingDbBenchmarkTest {
    @Test
    fun `notification to database latency`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = RoomLedgerRepository(database)
        val settings = object : NotificationSourceSettings {
            override val autoBookkeepingEnabled = MutableStateFlow(true)
            override val wechatEnabled = MutableStateFlow(true)
            override val alipayEnabled = MutableStateFlow(true)
            override val enabledBankPackages = MutableStateFlow(setOf("com.icbc"))
        }
        val router = NotificationRouter(settings)
        val processor = CandidateProcessor(repository)
        val base = Instant.parse("2026-11-19T04:00:00Z")

        suspend fun runOnce(index: Int): Boolean {
            val sample = SAMPLES[index % SAMPLES.size]
            val input = NotificationText(sample.first, sample.second, sample.third, base.plusSeconds(index.toLong()))
            val candidate = router.route(input) ?: return false
            return processor.process(candidate) != null
        }

        try {
            repeat(60) { runOnce(it) }
            val measured = 400
            val samples = LongArray(measured)
            for (index in 0 until measured) {
                val start = System.nanoTime()
                runOnce(10_000 + index)
                samples[index] = System.nanoTime() - start
            }
            val sorted = samples.copyOf().also { it.sort() }
            val p50 = sorted[(sorted.size * 0.50).toInt()]
            val p95 = sorted[(sorted.size * 0.95).toInt()]
            val p99 = sorted[(sorted.size * 0.99).toInt()]
            val mean = sorted.sum() / sorted.size
            val rows = repository.observeTransactions().first().size

            val report = buildString {
                appendLine("# 自动记账端到端基准（含 SQLite 落库）")
                appendLine()
                appendLine("| 指标 | 数值 |")
                appendLine("| --- | ---: |")
                appendLine("| 条数 | $measured |")
                appendLine("| 平均 | ${"%.2f".format(mean / 1_000_000.0)} ms |")
                appendLine("| p50 | ${"%.2f".format(p50 / 1_000_000.0)} ms |")
                appendLine("| p95 | ${"%.2f".format(p95 / 1_000_000.0)} ms |")
                appendLine("| p99 | ${"%.2f".format(p99 / 1_000_000.0)} ms |")
                appendLine("| 最大 | ${"%.2f".format(sorted.last() / 1_000_000.0)} ms |")
                appendLine("| 吞吐 | ${"%.0f".format(1_000_000_000.0 / mean.coerceAtLeast(1))} 条/秒 |")
                appendLine("| 落库总行数 | $rows 行 |")
            }
            val dir = File("build/benchmark").apply { mkdirs() }
            File(dir, "auto-bookkeeping-db-benchmark.md").writeText(report, Charsets.UTF_8)
            File(dir, "auto-bookkeeping-db-benchmark.txt").writeText(
                "mean=${mean / 1000};p50=${p50 / 1000};p95=${p95 / 1000};p99=${p99 / 1000};max=${sorted.last() / 1000};rows=$rows",
                Charsets.UTF_8,
            )
            println(report)
            assertTrue("p95 ${p95 / 1_000_000} ms", p95 < 200_000_000)
        } finally {
            database.close()
        }
    }

    private companion object {
        /** 标题、正文、包名 */
        val SAMPLES = listOf(
            Triple("微信支付", "已支付¥28.80", NotificationRouter.WECHAT_PACKAGE),
            Triple("微信支付", "支付成功 ￥128.00", NotificationRouter.WECHAT_PACKAGE),
            Triple("微信支付", "付款成功 ￥19.90，商户：瑞幸咖啡", NotificationRouter.WECHAT_PACKAGE),
            Triple("微信支付", "收款到账 ￥66.00", NotificationRouter.WECHAT_PACKAGE),
            Triple("支付宝", "支付成功 人民币68.00", NotificationRouter.ALIPAY_PACKAGE),
            Triple("支付宝", "退款到账 人民币10.00", NotificationRouter.ALIPAY_PACKAGE),
            Triple("账户变动", "消费人民币258.00", "com.icbc"),
            Triple("账户变动", "交易金额 人民币88.00", "com.icbc"),
        )
    }
}
