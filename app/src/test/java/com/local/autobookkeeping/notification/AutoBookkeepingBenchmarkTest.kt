package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.NotificationSourceSettings
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 自动记账链路的基准测试：设置读取 → 包名闸门 → 解析 → 去重 → 组装 → 入库。
 *
 * 用真实 `NotificationRouter` + `CandidateProcessor`，只把 Room 换成内存仓库，
 * 这样测出来的是解析与去重的纯 CPU 成本；落库成本见
 * [AutoBookkeepingDbBenchmarkTest]（真实 SQLite）。
 *
 * 报告写到 `app/build/benchmark/auto-bookkeeping-benchmark.{md,json}`。
 */
class AutoBookkeepingBenchmarkTest {
    private val warmupIterations = 4_000
    private val measuredIterations = 20_000

    @Test
    fun benchmark() = runBlocking {
        val settings = FakeSourceSettings()
        val router = NotificationRouter(settings)
        val base = Instant.parse("2026-11-19T04:00:00Z")

        val accuracy = measureAccuracy(router, base)
        val cold = measureCold(router, base)
        val warm = measureWarm(router, base)
        val parseOnly = measureParseOnly(router, base)
        val burst = measureBurst(router, base)
        val duplicate = measureDuplicate(router, base)

        val report = Report(accuracy, cold, warm, parseOnly, burst, duplicate)
        writeReport(report)
        println(report.toMarkdown())

        assertTrue(accuracy.regularHitRate >= 0.95, "常规样本命中率 ${accuracy.regularHitRate}")
        assertTrue(warm.p95Nanos < 5_000_000, "p95 ${warm.p95Nanos / 1_000}us 超过 5ms")
    }

    // ---------- 准确性 ----------

    private suspend fun measureAccuracy(
        router: NotificationRouter,
        base: Instant,
    ): Accuracy {
        val results = CORPUS.mapIndexed { index, sample ->
            val processorForCase = CandidateProcessor(InMemoryLedgerRepository())
            val input = sample.asNotification(base.plusSeconds(index.toLong() * 10))
            val candidate = router.route(input)
            val saved = candidate?.let { processorForCase.process(it) }
            CaseResult(
                sample = sample,
                candidate = candidate,
                saved = saved != null,
                pending = candidate?.let(CandidateProcessor::requiresConfirmation) ?: true,
            )
        }
        return Accuracy(results)
    }

    // ---------- 延迟 ----------

    private suspend fun measureCold(
        router: NotificationRouter,
        base: Instant,
    ): Latency {
        val processor = CandidateProcessor(InMemoryLedgerRepository())
        return latencyBlock("冷启动（前 20 条）", 20) { index ->
            runOnce(router, processor, base.plusSeconds(1_000 + index.toLong()))
        }
    }

    private suspend fun measureWarm(
        router: NotificationRouter,
        base: Instant,
    ): Latency {
        val warmupProcessor = CandidateProcessor(InMemoryLedgerRepository())
        repeat(warmupIterations) { index ->
            runOnce(router, warmupProcessor, base.plusSeconds(10_000 + index.toLong()))
        }
        val processor = CandidateProcessor(InMemoryLedgerRepository())
        val samples = LongArray(measuredIterations)
        val allocatedBefore = allocatedBytes()
        for (index in 0 until measuredIterations) {
            val start = System.nanoTime()
            runOnce(router, processor, base.plusSeconds(100_000 + index.toLong()))
            samples[index] = System.nanoTime() - start
        }
        val allocatedAfter = allocatedBytes()
        val perOp = if (allocatedBefore >= 0 && allocatedAfter > allocatedBefore) {
            (allocatedAfter - allocatedBefore) / measuredIterations
        } else {
            0L
        }
        return Latency.of("全链路（预热后 $measuredIterations 条）", samples).copy(allocatedBytes = perOp)
    }

    private suspend fun measureParseOnly(router: NotificationRouter, base: Instant): Latency {
        repeat(warmupIterations) { index ->
            router.route(CORPUS[index % CORPUS.size].asNotification(base.plusSeconds(200_000 + index.toLong())))
        }
        val samples = LongArray(measuredIterations)
        for (index in 0 until measuredIterations) {
            val input = CORPUS[index % CORPUS.size].asNotification(base.plusSeconds(300_000 + index.toLong()))
            val start = System.nanoTime()
            router.route(input)
            samples[index] = System.nanoTime() - start
        }
        return Latency.of("仅解析与包名闸门（$measuredIterations 条）", samples)
    }

    private suspend fun measureBurst(
        router: NotificationRouter,
        base: Instant,
    ): Burst {
        val samples = CORPUS.filter { it.expectation is Expectation.Match || it.expectation is Expectation.Ignore }
        val warmupProcessor = CandidateProcessor(InMemoryLedgerRepository())
        repeat(3) {
            samples.forEachIndexed { index, sample ->
                runOnce(router, warmupProcessor, base.plusSeconds(400_000 + index.toLong()))
            }
        }
        val processor = CandidateProcessor(InMemoryLedgerRepository())
        val start = System.nanoTime()
        var count = 0
        samples.forEachIndexed { index, sample ->
            runOnce(router, processor, base.plusSeconds(500_000 + index.toLong()))
            count += 1
        }
        val elapsed = System.nanoTime() - start
        return Burst(count = count, totalNanos = elapsed)
    }

    private suspend fun measureDuplicate(
        router: NotificationRouter,
        base: Instant,
    ): Duplicate {
        val repository = InMemoryLedgerRepository()
        val processor = CandidateProcessor(repository)
        val first = runAt(router, processor, CORPUS[0], base.plusSeconds(600_000))
        val second = runAt(router, processor, CORPUS[0], base.plusSeconds(600_000))
        val afterBucket = runAt(router, processor, CORPUS[0], base.plusSeconds(600_000 + 121))
        return Duplicate(
            firstSaved = first,
            duplicateSaved = second,
            afterBucketSaved = afterBucket,
            saves = repository.saves,
        )
    }

    private suspend fun runOnce(
        router: NotificationRouter,
        processor: CandidateProcessor,
        postedAt: Instant,
    ): Boolean = runAt(router, processor, CORPUS[(postedAt.epochSecond % CORPUS.size).toInt()], postedAt)

    private suspend fun runAt(
        router: NotificationRouter,
        processor: CandidateProcessor,
        sample: Sample,
        postedAt: Instant,
    ): Boolean {
        val candidate = router.route(sample.asNotification(postedAt)) ?: return false
        return processor.process(candidate) != null
    }

    private fun allocatedBytes(): Long = try {
        val bean = ManagementFactory.getThreadMXBean()
        if (bean is com.sun.management.ThreadMXBean) {
            bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
        } else {
            -1L
        }
    } catch (_: Throwable) {
        -1L
    }

    private suspend fun latencyBlock(
        name: String,
        iterations: Int,
        block: suspend (Int) -> Unit,
    ): Latency {
        val samples = LongArray(iterations)
        for (index in 0 until iterations) {
            val start = System.nanoTime()
            block(index)
            samples[index] = System.nanoTime() - start
        }
        return Latency.of(name, samples)
    }

    // ---------- 报告 ----------

    private fun writeReport(report: Report) {
        val dir = File("build/benchmark").apply { mkdirs() }
        File(dir, "auto-bookkeeping-benchmark.md").writeText(report.toMarkdown(), Charsets.UTF_8)
        File(dir, "auto-bookkeeping-benchmark.json").writeText(report.toJson(), Charsets.UTF_8)
    }

    data class Latency(
        val name: String,
        val count: Int,
        val meanNanos: Long,
        val p50Nanos: Long,
        val p95Nanos: Long,
        val p99Nanos: Long,
        val maxNanos: Long,
        val allocatedBytes: Long,
    ) {
        val throughputPerSecond: Double = 1_000_000_000.0 / meanNanos.coerceAtLeast(1)

        companion object {
            fun of(name: String, samples: LongArray): Latency {
                val sorted = samples.copyOf().also { it.sort() }
                val mean = if (sorted.isEmpty()) 0 else sorted.sum() / sorted.size
                return Latency(
                    name = name,
                    count = sorted.size,
                    meanNanos = mean,
                    p50Nanos = percentile(sorted, 0.50),
                    p95Nanos = percentile(sorted, 0.95),
                    p99Nanos = percentile(sorted, 0.99),
                    maxNanos = sorted.lastOrNull() ?: 0,
                    allocatedBytes = 0,
                )
            }

            private fun percentile(sorted: LongArray, p: Double): Long {
                if (sorted.isEmpty()) return 0
                val index = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)
                return sorted[index]
            }
        }
    }

    data class CaseResult(
        val sample: Sample,
        val candidate: PaymentCandidate?,
        val saved: Boolean,
        val pending: Boolean,
    ) {
        val passed: Boolean
            get() = when (val expectation = sample.expectation) {
                is Expectation.Match ->
                    candidate != null &&
                        candidate.amountMinor == expectation.amountMinor &&
                        candidate.direction == expectation.direction &&
                        candidate.eventKind == expectation.kind
                is Expectation.Ignore -> candidate == null
                is Expectation.Gap -> true
            }

        val failureReason: String?
            get() = if (passed) null else when (sample.expectation) {
                is Expectation.Ignore -> "误报：${candidate?.let { "${it.amountMinor / 100.0} ${it.eventKind}" }}"
                is Expectation.Match -> if (candidate == null) {
                    "漏记"
                } else {
                    "字段不符：${candidate.amountMinor / 100.0} / ${candidate.direction} / ${candidate.eventKind}"
                }
                is Expectation.Gap -> null
            }
    }

    data class Accuracy(val results: List<CaseResult>) {
        private val regular = results.filter { it.sample.group == Group.REGULAR }
        private val adversarial = results.filter { it.sample.group == Group.ADVERSARIAL }
        private val gaps = results.filter { it.sample.group == Group.GAP }
        private val matches = regular.filter { it.sample.expectation is Expectation.Match }

        val regularHitRate: Double = ratio(matches.count { it.passed }, matches.size)
        val regularFieldAccuracy: Double = ratio(
            matches.count { it.candidate?.amountMinor == (it.sample.expectation as Expectation.Match).amountMinor },
            matches.size,
        )
        val directSaveRate: Double = ratio(matches.count { it.passed && !it.pending }, matches.size)
        val adversarialFalsePositives: Int = adversarial.count { !it.passed }
        val adversarialRate: Int = adversarial.size
        val gapCases: Int = gaps.size

        val misses: List<String> = results.filter { !it.passed }
            .map { "${it.sample.title} / ${it.sample.body} → ${it.failureReason}" }

        private fun ratio(value: Int, total: Int): Double = if (total == 0) 0.0 else value.toDouble() / total
    }

    data class Burst(val count: Int, val totalNanos: Long) {
        val perItemMillis: Double = totalNanos / 1_000_000.0 / count.coerceAtLeast(1)
    }

    data class Duplicate(
        val firstSaved: Boolean,
        val duplicateSaved: Boolean,
        val afterBucketSaved: Boolean,
        val saves: Int,
    )

    data class Report(
        val accuracy: Accuracy,
        val cold: Latency,
        val warm: Latency,
        val parseOnly: Latency,
        val burst: Burst,
        val duplicate: Duplicate,
    ) {
        fun toMarkdown(): String = buildString {
            appendLine("# 自动记账链路基准")
            appendLine()
            appendLine("- JVM：${System.getProperty("java.version")} / 核心 ${Runtime.getRuntime().availableProcessors()}")
            appendLine("- 语料：${CORPUS.size} 条（常规 ${CORPUS.count { it.group == Group.REGULAR }}、" +
                "对抗 ${CORPUS.count { it.group == Group.ADVERSARIAL }}、" +
                "未覆盖 ${CORPUS.count { it.group == Group.GAP }}）")
            appendLine()
            appendLine("## 延迟")
            appendLine()
            appendLine("| 场景 | 条数 | 平均 | p50 | p95 | p99 | 最大 |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
            listOf(cold, warm, parseOnly).forEach { latency ->
                appendLine(
                    "| ${latency.name} | ${latency.count} | ${micros(latency.meanNanos)} | " +
                        "${micros(latency.p50Nanos)} | ${micros(latency.p95Nanos)} | " +
                        "${micros(latency.p99Nanos)} | ${micros(latency.maxNanos)} |",
                )
            }
            appendLine()
            appendLine("单线程吞吐：${"%.0f".format(warm.throughputPerSecond)} 条/秒；" +
                "解析占全链路 ${"%.0f".format(warm.meanNanos.let { p -> parseOnly.meanNanos * 100.0 / p })}%；" +
                "每条分配 ${"%.1f".format(warm.allocatedBytes / 1024.0)} KB。")
            appendLine()
            appendLine("## 突发")
            appendLine()
            appendLine("连续 ${burst.count} 条通知耗时 ${millis(burst.totalNanos)}，平均 ${"%.2f".format(burst.perItemMillis)} ms/条。")
            appendLine()
            appendLine("## 准确性")
            appendLine()
            appendLine("| 指标 | 数值 |")
            appendLine("| --- | --- |")
            appendLine("| 常规样本命中率 | ${percent(accuracy.regularHitRate)} |")
            appendLine("| 金额正确率 | ${percent(accuracy.regularFieldAccuracy)} |")
            appendLine("| 直接入账（无需核对）比例 | ${percent(accuracy.directSaveRate)} |")
            appendLine("| 对抗样本误报 | ${accuracy.adversarialFalsePositives} / ${accuracy.adversarialRate} |")
            appendLine("| 已知未覆盖 | ${accuracy.gapCases} |")
            appendLine()
            if (accuracy.misses.isNotEmpty()) {
                appendLine("失败明细：")
                accuracy.misses.forEach { appendLine("- $it") }
                appendLine()
            }
            appendLine("## 去重")
            appendLine()
            appendLine(
                "首条入库=${duplicate.firstSaved}，同内容重复投递入库=${duplicate.duplicateSaved}，" +
                    "跨 2 分钟窗口再投递入库=${duplicate.afterBucketSaved}，仓库写入次数=${duplicate.saves}。",
            )
        }

        fun toJson(): String = buildString {
            appendLine("{")
            appendLine("  \"jvm\": \"${System.getProperty("java.version")}\",")
            appendLine("  \"corpus\": ${CORPUS.size},")
            appendLine("  \"latencyMicros\": {")
            appendLine("    \"cold\": ${cold.toJson()},")
            appendLine("    \"warm\": ${warm.toJson()},")
            appendLine("    \"parseOnly\": ${parseOnly.toJson()}")
            appendLine("  },")
            appendLine("  \"throughputPerSecond\": ${"%.1f".format(warm.throughputPerSecond)},")
            appendLine("  \"burst\": { \"count\": ${burst.count}, \"totalMillis\": ${"%.2f".format(burst.totalNanos / 1_000_000.0)}, \"perItemMillis\": ${"%.2f".format(burst.perItemMillis)} },")
            appendLine("  \"accuracy\": {")
            appendLine("    \"regularHitRate\": ${"%.4f".format(accuracy.regularHitRate)},")
            appendLine("    \"amountAccuracy\": ${"%.4f".format(accuracy.regularFieldAccuracy)},")
            appendLine("    \"directSaveRate\": ${"%.4f".format(accuracy.directSaveRate)},")
            appendLine("    \"adversarialFalsePositives\": ${accuracy.adversarialFalsePositives},")
            appendLine("    \"adversarialTotal\": ${accuracy.adversarialRate}")
            appendLine("  },")
            appendLine("  \"duplicate\": { \"firstSaved\": ${duplicate.firstSaved}, \"duplicateSaved\": ${duplicate.duplicateSaved}, \"afterBucketSaved\": ${duplicate.afterBucketSaved}, \"saves\": ${duplicate.saves} }")
            appendLine("}")
        }

        private fun Latency.toJson(): String =
            "{ \"count\": $count, \"mean\": ${meanNanos / 1000}, \"p50\": ${p50Nanos / 1000}, " +
                "\"p95\": ${p95Nanos / 1000}, \"p99\": ${p99Nanos / 1000}, \"max\": ${maxNanos / 1000} }"

        private fun micros(nanos: Long): String = "%.1f µs".format(nanos / 1_000.0)
        private fun millis(nanos: Long): String = "%.1f ms".format(nanos / 1_000_000.0)
        private fun percent(value: Double): String = "%.0f%%".format(value * 100)
    }

    // ---------- 语料 ----------

    enum class Group { REGULAR, ADVERSARIAL, GAP }

    sealed interface Expectation {
        data class Match(
            val amountMinor: Long,
            val direction: Direction?,
            val kind: PaymentEventKind,
        ) : Expectation {
            constructor(yuan: String, direction: Direction?, kind: PaymentEventKind) : this(
                (java.math.BigDecimal(yuan).movePointRight(2).longValueExact()),
                direction,
                kind,
            )
        }

        data object Ignore : Expectation
        data object Gap : Expectation
    }

    data class Sample(
        val packageName: String,
        val title: String,
        val body: String,
        val expectation: Expectation,
        val group: Group = Group.REGULAR,
    ) {
        fun asNotification(postedAt: Instant) = NotificationText(
            title = title,
            body = body,
            packageName = packageName,
            postedAt = postedAt,
        )
    }

    private class FakeSourceSettings(
        private val banks: Set<String> = setOf(ICBC, CMB),
    ) : NotificationSourceSettings {
        override val autoBookkeepingEnabled = MutableStateFlow(true)
        override val wechatEnabled = MutableStateFlow(true)
        override val alipayEnabled = MutableStateFlow(true)
        override val enabledBankPackages = MutableStateFlow(banks)
    }

    private class InMemoryLedgerRepository : LedgerRepository {
        private val byDedupeKey = HashMap<String, Transaction>()
        var saves: Int = 0
            private set

        override fun observeTransactions(): Flow<List<Transaction>> = emptyFlow()

        override suspend fun save(transaction: Transaction): Long {
            saves += 1
            transaction.dedupeKey?.let { byDedupeKey[it] = transaction }
            return saves.toLong()
        }

        override suspend fun delete(id: Long) = Unit

        override suspend fun findByDedupeKey(key: String): Transaction? = byDedupeKey[key]

        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) = Unit
    }

    private companion object {
        const val WECHAT = NotificationRouter.WECHAT_PACKAGE
        const val ALIPAY = NotificationRouter.ALIPAY_PACKAGE
        const val ICBC = "com.icbc"
        const val CMB = "com.cmbchina"

        val CORPUS = listOf(
            // 微信支付
            Sample(WECHAT, "微信支付", "已支付¥1.00", Expectation.Match("1.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "已支付¥28.80", Expectation.Match("28.80", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "已支付 ￥1,280.00", Expectation.Match("1280.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "支付成功 ￥28.00", Expectation.Match("28.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "付款成功 ￥19.90，商户：瑞幸咖啡", Expectation.Match("19.90", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "收款到账 ￥66.00", Expectation.Match("66.00", Direction.INCOME, PaymentEventKind.RECEIPT)),
            // 微信的营销与冲突文案必须挡掉
            Sample(WECHAT, "微信支付", "已支付¥1.00，优惠¥2.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "微信支付", "已支付¥1.00，领取优惠券", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "微信支付", "已支付¥1.00，退款到账", Expectation.Ignore, Group.ADVERSARIAL),
            // 支付宝
            Sample(ALIPAY, "支付宝", "支付成功 人民币28.00", Expectation.Match("28.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(ALIPAY, "支付宝", "付款成功 ￥9.90，商户：全家便利店", Expectation.Match("9.90", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(ALIPAY, "支付宝", "退款到账 人民币10.00", Expectation.Match("10.00", Direction.INCOME, PaymentEventKind.REFUND)),
            Sample(ALIPAY, "支付宝", "转账到账 人民币20.00", Expectation.Match("20.00", Direction.INCOME, PaymentEventKind.TRANSFER)),
            Sample(ALIPAY, "支付宝", "转账已支付 人民币30.00", Expectation.Match("30.00", Direction.EXPENSE, PaymentEventKind.TRANSFER)),
            Sample(ALIPAY, "支付宝", "收到红包 人民币40.00", Expectation.Match("40.00", Direction.INCOME, PaymentEventKind.RED_PACKET)),
            Sample(ALIPAY, "支付宝", "红包已支付 人民币50.00", Expectation.Match("50.00", Direction.EXPENSE, PaymentEventKind.RED_PACKET)),
            Sample(ALIPAY, "支付宝", "收款成功 人民币88.00", Expectation.Match("88.00", Direction.INCOME, PaymentEventKind.RECEIPT)),
            Sample(ALIPAY, "支付宝", "您有一张￥5.00优惠券待领取", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(ALIPAY, "支付宝", "花呗账单 人民币1,280.00 待还", Expectation.Ignore, Group.ADVERSARIAL),
            // 银行
            Sample(ICBC, "账户变动", "消费人民币68.50", Expectation.Match("68.50", Direction.EXPENSE, PaymentEventKind.DEBIT)),
            Sample(ICBC, "交易提醒", "收入 CNY 100.00", Expectation.Match("100.00", Direction.INCOME, PaymentEventKind.CREDIT)),
            Sample(ICBC, "账户变动", "退款入账人民币28.00，商户：便利店", Expectation.Match("28.00", Direction.INCOME, PaymentEventKind.REFUND)),
            Sample(ICBC, "账户变动", "交易金额 人民币88.00", Expectation.Match("88.00", null, PaymentEventKind.AMBIGUOUS)),
            Sample(ICBC, "活动通知", "优惠券价值 人民币88.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(CMB, "招商银行", "您尾号8899的账户消费 人民币258.00", Expectation.Match("258.00", Direction.EXPENSE, PaymentEventKind.DEBIT)),
            Sample(CMB, "招商银行", "您尾号8899账户入账 人民币5,000.00", Expectation.Match("5000.00", Direction.INCOME, PaymentEventKind.CREDIT)),
            // 聊天里的金额：不能当作账单
            Sample(WECHAT, "张三", "我已支付¥1.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "家庭群", "今晚AA，每人￥50.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "房东", "这个月房租￥2,500 记得转我", Expectation.Ignore, Group.ADVERSARIAL),
            // 已知未覆盖（结果不参与命中率）
            Sample(ALIPAY, "支付宝", "余额宝昨日收益 人民币0.35", Expectation.Gap, Group.GAP),
        )
    }
}
