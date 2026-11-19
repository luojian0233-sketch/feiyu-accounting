package com.local.autobookkeeping.notification

import com.local.autobookkeeping.data.MerchantRuleMatch
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.recognition.AutoSavePolicy
import com.local.autobookkeeping.recognition.MerchantClassifier
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 生产链路基准：通知 → `NotificationRouter` → `RecognitionCoordinator`（去重 / 匹配 / 分类 / 自动保存）。
 *
 * 与 `AutoBookkeepingBenchmarkTest` 的区别：那个量的是解析 + 旧 `CandidateProcessor`，
 * 这里量的是 App 真正注册在 `AutoBookkeepingApplication` 里的入口。
 *
 * 另外单独统计「有没有商户规则」对自动入账率的影响——这是决定要不要做学习闭环的依据。
 */
class RealAutoBookkeepingBenchmarkTest {
    private val warmup = 3_000
    private val measured = 20_000

    @Test
    fun benchmark() = runBlocking {
        val router = NotificationRouter(FakeSourceSettings())
        val base = Instant.parse("2026-11-19T04:00:00Z")

        val fresh = runCorpus(router, base, merchantRules = emptySet())
        val learned = runCorpus(router, base, merchantRules = LEARNED_MERCHANTS)

        val cold = measureCold(router, base)
        val warm = measureWarm(router, base)
        val burst = measureBurst(router, base)
        val duplicate = measureDuplicate(router, base)

        val report = buildString {
            appendLine("# 自动记账生产链路基准")
            appendLine()
            appendLine("- JVM：${System.getProperty("java.version")} / 核心 ${Runtime.getRuntime().availableProcessors()}")
            appendLine("- 语料：${CORPUS.size} 条（常规 ${CORPUS.count { it.group == Group.REGULAR }}、" +
                "对抗 ${CORPUS.count { it.group == Group.ADVERSARIAL }}）")
            appendLine()
            appendLine("## 延迟（含 coordinator 的去重、匹配、分类）")
            appendLine()
            appendLine("| 场景 | 条数 | 平均 | p50 | p95 | p99 |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
            listOf(cold, warm).forEach {
                appendLine(
                    "| ${it.name} | ${it.count} | ${us(it.mean)} | ${us(it.p50)} | ${us(it.p95)} | ${us(it.p99)} |",
                )
            }
            appendLine()
            appendLine("单线程吞吐：${"%.0f".format(1_000_000_000.0 / warm.mean.coerceAtLeast(1))} 条/秒")
            appendLine()
            appendLine("## 自动入账率")
            appendLine()
            appendLine("| 商户规则 | 命中 | 自动入账 | 待核对 | 备注 |")
            appendLine("| --- | ---: | ---: | ---: | --- |")
            appendLine("| 全新安装（0 条规则） | ${fresh.hit}/${fresh.total} | ${fresh.autoSaved} | ${fresh.pending} | " +
                "支付通知没有商户名，也没有分类可用 |")
            appendLine("| 已有 ${LEARNED_MERCHANTS.size} 条商户规则 | ${learned.hit}/${learned.total} | ${learned.autoSaved} | ${learned.pending} | " +
                "只有带「商户：」的通知能吃到规则 |")
            appendLine()
            appendLine("分摊：${fresh.breakdown}")
            appendLine()
            appendLine("## 突发与去重")
            appendLine()
            appendLine("连续 ${burst.count} 条：${"%.1f".format(burst.totalNanos / 1_000_000.0)} ms（${"%.2f".format(burst.perItemMicros)} µs/条）。")
            appendLine()
            appendLine(
                "同一条通知重复投递：首条=${duplicate.first}，重复=${duplicate.second}，" +
                    "跨 2 分钟再投递=${duplicate.afterBucket}。",
            )
            if (fresh.misses.isNotEmpty()) {
                appendLine()
                appendLine("未按预期命中：")
                fresh.misses.forEach { appendLine("- $it") }
            }
        }
        val dir = File("build/benchmark").apply { mkdirs() }
        File(dir, "auto-bookkeeping-real-path.md").writeText(report, Charsets.UTF_8)
        println(report)

        assertEquals(CORPUS.count { it.group == Group.REGULAR }, fresh.hit, "常规样本应全部命中")
        assertTrue(warm.p95 < 5_000_000, "p95 ${warm.p95 / 1_000}us 超过 5ms")
    }

    // ---------- 入账率 ----------

    private suspend fun runCorpus(
        router: NotificationRouter,
        base: Instant,
        merchantRules: Set<String>,
    ): Batch {
        val store = FakeRecognitionStore(merchantRules)
        val coordinator = RecognitionCoordinator(store, AutoSavePolicy { true }, MerchantClassifier(store))
        val counts = mutableMapOf<String, Int>()
        val misses = mutableListOf<String>()
        var hit = 0
        var autoSaved = 0
        var pending = 0
        var total = 0

        CORPUS.forEachIndexed { index, sample ->
            if (sample.group != Group.REGULAR) return@forEachIndexed
            total += 1
            val input = sample.asNotification(base.plusSeconds(index.toLong() * 10L))
            val candidate = router.route(input)
            val outcome = candidate?.let { coordinator.process(it.toRecognitionCandidate()) }
            val saved = outcome as? RecognitionOutcome.Saved
            when {
                sample.expectation is Expectation.Match && candidate != null && saved != null -> hit += 1
                else -> misses += "${sample.title} / ${sample.body} → ${describe(outcome)}"
            }
            if (saved != null) {
                if (saved.pending) pending += 1 else autoSaved += 1
            }
            val key = when (outcome) {
                is RecognitionOutcome.Saved -> if (outcome.pending) "待核对" else "自动入账"
                RecognitionOutcome.Duplicate -> "重复"
                is RecognitionOutcome.NeedsSelection -> "需选择"
                is RecognitionOutcome.Rejected -> "拒收(${outcome.reason})"
                null -> "未识别"
            }
            counts[key] = (counts[key] ?: 0) + 1
        }
        return Batch(
            total = total,
            hit = hit,
            autoSaved = autoSaved,
            pending = pending,
            misses = misses,
            breakdown = counts.entries.joinToString("、") { "${it.key} ${it.value}" },
        )
    }

    private fun describe(outcome: RecognitionOutcome?): String = when (outcome) {
        is RecognitionOutcome.Saved -> if (outcome.pending) "待核对" else "自动入账"
        RecognitionOutcome.Duplicate -> "重复"
        is RecognitionOutcome.NeedsSelection -> "需选择"
        is RecognitionOutcome.Rejected -> "拒收 ${outcome.reason}"
        null -> "未识别"
    }

    // ---------- 延迟 ----------

    private suspend fun measureCold(router: NotificationRouter, base: Instant): Stats {
        val coordinator = coordinator(FakeRecognitionStore(emptySet()))
        return timing("冷启动（前 20 条）", 20) { index ->
            once(router, coordinator, base.plusSeconds(1_000 + index.toLong()))
        }
    }

    private suspend fun measureWarm(router: NotificationRouter, base: Instant): Stats {
        val warmStore = FakeRecognitionStore(LEARNED_MERCHANTS)
        val warmCoordinator = coordinator(warmStore)
        repeat(warmup) { index ->
            once(router, warmCoordinator, base.plusSeconds(10_000 + index.toLong()))
        }
        val coordinator = coordinator(FakeRecognitionStore(LEARNED_MERCHANTS))
        return timing("预热后（$measured 条）", measured) { index ->
            once(router, coordinator, base.plusSeconds(100_000 + index.toLong()))
        }
    }

    private suspend fun measureBurst(router: NotificationRouter, base: Instant): Burst {
        val samples = CORPUS.filter { it.group == Group.REGULAR }
        val coordinator = coordinator(FakeRecognitionStore(LEARNED_MERCHANTS))
        repeat(2) {
            samples.forEachIndexed { index, sample ->
                once(router, coordinator, sample, base.plusSeconds(400_000 + index.toLong()))
            }
        }
        val fresh = coordinator(FakeRecognitionStore(LEARNED_MERCHANTS))
        val start = System.nanoTime()
        samples.forEachIndexed { index, sample ->
            once(router, fresh, sample, base.plusSeconds(500_000 + index.toLong()))
        }
        val elapsed = System.nanoTime() - start
        return Burst(samples.size, elapsed)
    }

    private suspend fun measureDuplicate(router: NotificationRouter, base: Instant): Duplicate {
        val store = FakeRecognitionStore(LEARNED_MERCHANTS)
        val coordinator = coordinator(store)
        val sample = CORPUS.first { it.group == Group.REGULAR }
        val first = once(router, coordinator, sample, base.plusSeconds(600_000)) != null
        val second = once(router, coordinator, sample, base.plusSeconds(600_000))
        val afterBucket = once(router, coordinator, sample, base.plusSeconds(600_000 + 121)) != null
        return Duplicate(
            first = first,
            second = second is RecognitionOutcome.Duplicate || second == null,
            afterBucket = afterBucket,
        )
    }

    private fun coordinator(store: FakeRecognitionStore) =
        RecognitionCoordinator(store, AutoSavePolicy { true }, MerchantClassifier(store))

    private suspend fun once(
        router: NotificationRouter,
        coordinator: RecognitionCoordinator,
        postedAt: Instant,
    ): RecognitionOutcome? {
        val sample = CORPUS[(postedAt.epochSecond % CORPUS.size).toInt()]
        return once(router, coordinator, sample, postedAt)
    }

    private suspend fun once(
        router: NotificationRouter,
        coordinator: RecognitionCoordinator,
        sample: Sample,
        postedAt: Instant,
    ): RecognitionOutcome? {
        val candidate = router.route(sample.asNotification(postedAt)) ?: return null
        return coordinator.process(candidate.toRecognitionCandidate())
    }

    private suspend fun timing(name: String, count: Int, block: suspend (Int) -> Unit): Stats {
        val samples = LongArray(count)
        for (index in 0 until count) {
            val start = System.nanoTime()
            block(index)
            samples[index] = System.nanoTime() - start
        }
        val sorted = samples.copyOf().also { it.sort() }
        return Stats(
            name = name,
            count = count,
            mean = sorted.sum() / count,
            p50 = sorted[(count * 0.50).toInt().coerceIn(0, count - 1)],
            p95 = sorted[(count * 0.95).toInt().coerceIn(0, count - 1)],
            p99 = sorted[(count * 0.99).toInt().coerceIn(0, count - 1)],
        )
    }

    private fun us(nanos: Long) = "%.1f µs".format(nanos / 1_000.0)

    data class Stats(val name: String, val count: Int, val mean: Long, val p50: Long, val p95: Long, val p99: Long)
    data class Duplicate(val first: Boolean, val second: Boolean, val afterBucket: Boolean)
    data class Burst(val count: Int, val totalNanos: Long) {
        val perItemMicros: Double = totalNanos / 1_000.0 / count.coerceAtLeast(1)
    }
    data class Batch(
        val total: Int,
        val hit: Int,
        val autoSaved: Int,
        val pending: Int,
        val misses: List<String>,
        val breakdown: String,
    )

    // ---------- 假仓库 ----------

    /** 只实现 coordinator 会用到的部分：去重收据、匹配、商户规则、分类可见性。 */
    private class FakeRecognitionStore(private val rules: Set<String>) : RecognitionStore {
        private val receipts = mutableListOf<RecognitionReceipt>()
        private val transactions = mutableListOf<Transaction>()
        private val visible = setOf("餐饮", "退款", "转账", "红包", "通讯", "其他", "购物", "交通")

        override suspend fun findReceipt(digest: String): RecognitionReceipt? =
            receipts.firstOrNull { it.digest == digest }

        override suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction> =
            transactions.filter {
                it.platform == query.platform &&
                    it.direction == query.direction &&
                    it.amountMinor == query.amountMinor &&
                    it.eventKind == query.eventKind &&
                    !it.occurredAt.isBefore(query.from) &&
                    !it.occurredAt.isAfter(query.to)
            }

        override suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long {
            val id = (transactions.maxOfOrNull { it.id } ?: 0L) + 1
            transactions += transaction.copy(id = id)
            receipts += receipt.copy(transactionId = id)
            return id
        }

        override suspend fun update(transaction: Transaction, receipt: RecognitionReceipt) {
            val index = transactions.indexOfFirst { it.id == transaction.id }
            if (index >= 0) transactions[index] = transaction
            receipts += receipt.copy(transactionId = transaction.id)
        }

        override suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt) {
            receipts += receipt.copy(transactionId = transactionId)
        }

        override suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch? =
            if (normalizedMerchant in rules) {
                MerchantRuleMatch(normalizedMerchant, "餐饮", null, 0)
            } else {
                null
            }

        override suspend fun isCategoryVisible(name: String): Boolean = name in visible

        override suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction? = null

        override suspend fun rememberMerchantRule(merchant: String, category: String, account: String?) = Unit

        override suspend fun confirmAndRemember(transaction: Transaction): Long = transaction.id
    }

    private class FakeSourceSettings : com.local.autobookkeeping.data.NotificationSourceSettings {
        override val autoBookkeepingEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val wechatEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val alipayEnabled = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val enabledBankPackages = kotlinx.coroutines.flow.MutableStateFlow(setOf("com.icbc", "com.cmbchina"))
    }

    // ---------- 语料（与 AutoBookkeepingBenchmarkTest 同步） ----------

    enum class Group { REGULAR, ADVERSARIAL }

    sealed interface Expectation {
        data class Match(
            val amountMinor: Long,
            val direction: Direction?,
            val kind: PaymentEventKind,
        ) : Expectation {
            constructor(yuan: String, direction: Direction?, kind: PaymentEventKind) : this(
                java.math.BigDecimal(yuan).movePointRight(2).longValueExact(),
                direction,
                kind,
            )
        }

        data object Ignore : Expectation
    }

    data class Sample(
        val packageName: String,
        val title: String,
        val body: String,
        val expectation: Expectation,
        val group: Group = Group.REGULAR,
    ) {
        fun asNotification(postedAt: Instant) = NotificationText(title, body, packageName, postedAt)
    }

    companion object {
        private const val WECHAT = NotificationRouter.WECHAT_PACKAGE
        private const val ALIPAY = NotificationRouter.ALIPAY_PACKAGE
        private const val ICBC = "com.icbc"
        private const val CMB = "com.cmbchina"

        /** 语料里出现过的、带「商户：」的商户名（`MerchantClassifier` 会先 lowercase 再去查）。 */
        val LEARNED_MERCHANTS = setOf("瑞幸咖啡", "全家便利店", "便利店")

        val CORPUS = listOf(
            Sample(WECHAT, "微信支付", "已支付¥1.00", Expectation.Match("1.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "已支付¥28.80", Expectation.Match("28.80", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "已支付 ￥1,280.00", Expectation.Match("1280.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "支付成功 ￥28.00", Expectation.Match("28.00", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "付款成功 ￥19.90，商户：瑞幸咖啡", Expectation.Match("19.90", Direction.EXPENSE, PaymentEventKind.PAYMENT)),
            Sample(WECHAT, "微信支付", "收款到账 ￥66.00", Expectation.Match("66.00", Direction.INCOME, PaymentEventKind.RECEIPT)),
            Sample(WECHAT, "微信支付", "已支付¥1.00，优惠¥2.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "微信支付", "已支付¥1.00，领取优惠券", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "微信支付", "已支付¥1.00，退款到账", Expectation.Ignore, Group.ADVERSARIAL),
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
            Sample(ICBC, "账户变动", "消费人民币68.50", Expectation.Match("68.50", Direction.EXPENSE, PaymentEventKind.DEBIT)),
            Sample(ICBC, "交易提醒", "收入 CNY 100.00", Expectation.Match("100.00", Direction.INCOME, PaymentEventKind.CREDIT)),
            Sample(ICBC, "账户变动", "退款入账人民币28.00，商户：便利店", Expectation.Match("28.00", Direction.INCOME, PaymentEventKind.REFUND)),
            Sample(ICBC, "账户变动", "交易金额 人民币88.00", Expectation.Match("88.00", null, PaymentEventKind.AMBIGUOUS)),
            Sample(ICBC, "活动通知", "优惠券价值 人民币88.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(CMB, "招商银行", "您尾号8899的账户消费 人民币258.00", Expectation.Match("258.00", Direction.EXPENSE, PaymentEventKind.DEBIT)),
            Sample(CMB, "招商银行", "您尾号8899账户入账 人民币5,000.00", Expectation.Match("5000.00", Direction.INCOME, PaymentEventKind.CREDIT)),
            Sample(WECHAT, "张三", "我已支付¥1.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "家庭群", "今晚AA，每人￥50.00", Expectation.Ignore, Group.ADVERSARIAL),
            Sample(WECHAT, "房东", "这个月房租￥2,500 记得转我", Expectation.Ignore, Group.ADVERSARIAL),
        )
    }
}
