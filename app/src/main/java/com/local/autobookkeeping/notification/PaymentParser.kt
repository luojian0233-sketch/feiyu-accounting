package com.local.autobookkeeping.notification

import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.recognition.isLearnableMerchant
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

data class NotificationText(
    val title: String,
    val body: String,
    val packageName: String,
    val postedAt: Instant,
) {
    override fun toString(): String =
        "NotificationText(title=<redacted>, body=<redacted>, packageName=$packageName, postedAt=$postedAt)"
}

data class PaymentCandidate(
    val amountMinor: Long,
    val direction: Direction?,
    val merchant: String?,
    val occurredAt: Instant,
    val sourcePackage: String,
    val confidence: Int,
    val redactedDigest: String,
    val dedupeKey: String,
    val eventKind: PaymentEventKind,
) {
    init {
        require(amountMinor > 0)
        require(sourcePackage.isNotBlank())
        require(confidence in 0..100)
        require(redactedDigest.matches(SHA_256_HEX))
        require(dedupeKey.matches(SHA_256_HEX))
    }

    override fun toString(): String =
        "PaymentCandidate(" +
            "amountMinor=$amountMinor, " +
            "direction=$direction, " +
            "merchant=<redacted>, " +
            "occurredAt=$occurredAt, " +
            "sourcePackage=$sourcePackage, " +
            "confidence=$confidence, " +
            "eventKind=$eventKind)"
}

enum class PaymentEventKind {
    PAYMENT,
    RECEIPT,
    REFUND,
    DEBIT,
    CREDIT,
    TRANSFER,
    RED_PACKET,
    AMBIGUOUS,
}

fun interface PaymentParser {
    fun parse(notification: NotificationText): PaymentCandidate?
}

private data class KeywordSignal(
    val direction: Direction,
    val range: IntRange,
    val eventKind: PaymentEventKind,
)

private data class ParsedAmount(
    val minor: Long,
    val range: IntRange,
)

private val CURRENCY_TOKEN = Regex("(?:人民币|CNY|RMB|[¥￥])", RegexOption.IGNORE_CASE)
private val AMOUNT_AFTER_TOKEN = Regex("(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?")
private val SHA_256_HEX = Regex("[0-9a-f]{64}")
private val PROMOTION_KEYWORDS = setOf(
    "满减",
    "优惠",
    "活动",
    "最高",
    "可享",
    "promotion",
    "coupon",
    "offer",
)
private val ACTUAL_AMOUNT_LABELS = setOf(
    "实付",
    "交易金额",
    "支付金额",
    "消费金额",
    "收款金额",
    "入账金额",
    "amount paid",
    "transaction amount",
)
private val BILL_CONTEXT_KEYWORDS = setOf(
    "信用卡账单",
    "贷记卡账单",
    "信用卡还款",
    "贷记卡还款",
    "credit card payment",
    "credit card bill",
    "repayment",
)
private val TRANSFER_KEYWORDS = setOf("转入", "转出", "转账", "transfer")
private val SIGN_MARKERS = setOf('+', '-', '−', '－', '﹣', '负')
private val MERCHANT_PATTERNS = listOf(
    Regex("(?:商户|收款方)\\s*[：:]\\s*([\\p{L}\\p{N}·&（）()\\-]{2,32})(?=$|[，,。；;\\n])"),
    Regex("付款给\\s*([\\p{L}\\p{N}·&（）()\\-]{2,32})(?=$|[，,。；;\\n])"),
)

internal fun parseCnyMinor(text: String): Long? = findCnyAmounts(text).singleOrNull()?.minor

internal fun parseSourceCandidate(
    notification: NotificationText,
    expenseKeywords: Set<String>,
    incomeKeywords: Set<String>,
    fallbackContextKeywords: Set<String> = emptySet(),
    expenseEventKind: PaymentEventKind = PaymentEventKind.PAYMENT,
    incomeEventKind: PaymentEventKind = PaymentEventKind.RECEIPT,
    refundKeywords: Set<String> = emptySet(),
    expenseRedPacketKeywords: Set<String> = emptySet(),
    incomeRedPacketKeywords: Set<String> = emptySet(),
    transferKeywords: Set<String> = TRANSFER_KEYWORDS,
    clearConfidence: Int,
    ambiguousConfidence: Int,
): PaymentCandidate? {
    val text = notification.title + "\n" + notification.body
    val normalized = text.lowercase(Locale.ROOT)
    if (normalized.containsAny(BILL_CONTEXT_KEYWORDS)) return null
    val amountLabelRanges = ACTUAL_AMOUNT_LABELS.flatMap { normalized.rangesOf(it) }
    if (normalized.containsAny(PROMOTION_KEYWORDS) && amountLabelRanges.isEmpty()) return null

    val transferSignals = findSignals(
        normalized,
        transferKeywords,
        Direction.EXPENSE,
        PaymentEventKind.TRANSFER,
    )
    val expenseSignals = findSignals(normalized, expenseKeywords, Direction.EXPENSE, expenseEventKind)
    val incomeSignals = findSignals(normalized, incomeKeywords, Direction.INCOME, incomeEventKind)
    val refundSignals = findSignals(normalized, refundKeywords, Direction.INCOME, PaymentEventKind.REFUND)
    val expenseRedPacketSignals = findSignals(
        normalized,
        expenseRedPacketKeywords,
        Direction.EXPENSE,
        PaymentEventKind.RED_PACKET,
    )
    val incomeRedPacketSignals = findSignals(
        normalized,
        incomeRedPacketKeywords,
        Direction.INCOME,
        PaymentEventKind.RED_PACKET,
    )
    val redPacketSignals = expenseRedPacketSignals + incomeRedPacketSignals
    val signals = expenseSignals + incomeSignals + refundSignals + redPacketSignals + transferSignals
    val contextRanges = fallbackContextKeywords.flatMap { keyword ->
        normalized.rangesOf(keyword.lowercase(Locale.ROOT))
    }
    if (signals.isEmpty() && contextRanges.isEmpty()) return null

    val amounts = findCnyAmounts(text)
    if (amounts.isEmpty()) return null
    val amount = selectSafelyAssociatedAmount(
        text = text,
        amounts = amounts,
        labelRanges = amountLabelRanges,
        signalRanges = signals.map(KeywordSignal::range),
        contextRanges = contextRanges,
    ) ?: return null
    val directions = signals.filterNot { it.eventKind == PaymentEventKind.TRANSFER }
        .map(KeywordSignal::direction)
        .toSet()
    val transferDirection = when {
        normalized.contains("转入") -> Direction.INCOME
        normalized.contains("转出") -> Direction.EXPENSE
        else -> null
    }
    val direction = directions.singleOrNull()
        ?: transferDirection
    val signalKinds = signals.filterNot { it.eventKind == PaymentEventKind.TRANSFER }
        .map(KeywordSignal::eventKind)
        .toSet()
    val eventKind = when {
        transferSignals.isNotEmpty() -> PaymentEventKind.TRANSFER
        direction == null -> PaymentEventKind.AMBIGUOUS
        refundSignals.isNotEmpty() && expenseSignals.isEmpty() -> PaymentEventKind.REFUND
        redPacketSignals.isNotEmpty() && signalKinds == setOf(PaymentEventKind.RED_PACKET) ->
            PaymentEventKind.RED_PACKET
        signalKinds.size != 1 -> PaymentEventKind.AMBIGUOUS
        else -> signalKinds.single()
    }
    val merchant = extractMerchant(text)
    val confidence = when {
        eventKind == PaymentEventKind.TRANSFER -> ambiguousConfidence
        direction == null || eventKind == PaymentEventKind.AMBIGUOUS -> ambiguousConfidence
        merchant == null -> ambiguousConfidence
        else -> clearConfidence
    }
    return buildCandidate(
        notification = notification,
        amountMinor = amount.minor,
        direction = direction,
        confidence = confidence,
        merchant = merchant,
        eventKind = eventKind,
    )
}

internal fun buildCandidate(
    notification: NotificationText,
    amountMinor: Long,
    direction: Direction?,
    confidence: Int,
    merchant: String?,
    eventKind: PaymentEventKind,
): PaymentCandidate {
    val bucket = notification.postedAt.epochSecond / DEDUPE_BUCKET_SECONDS
    val eventSignal = "${notification.packageName}:${eventKind.name}"
    val signalFields = listOf(
        notification.packageName,
        amountMinor.toString(),
        eventSignal,
        direction?.name ?: "UNSPECIFIED",
        bucket.toString(),
        notification.postedAt.epochSecond.toString(),
    ).joinToString("|")
    return PaymentCandidate(
        amountMinor = amountMinor,
        direction = direction,
        merchant = merchant,
        occurredAt = notification.postedAt,
        sourcePackage = notification.packageName,
        confidence = confidence,
        redactedDigest = "digest-v1|$signalFields".sha256(),
        dedupeKey = "dedupe-v1|$signalFields".sha256(),
        eventKind = eventKind,
    )
}

private fun String.containsAny(keywords: Set<String>): Boolean = keywords.any(::contains)

private fun findSignals(
    normalizedText: String,
    keywords: Set<String>,
    direction: Direction,
    eventKind: PaymentEventKind,
): List<KeywordSignal> = keywords.flatMap { keyword ->
    normalizedText.rangesOf(keyword.lowercase(Locale.ROOT)).map { range ->
        KeywordSignal(direction, range, eventKind)
    }
}

private data class AmountAssociation(val amount: ParsedAmount, val rank: Int, val distance: Int)

private fun selectSafelyAssociatedAmount(
    text: String,
    amounts: List<ParsedAmount>,
    labelRanges: List<IntRange>,
    signalRanges: List<IntRange>,
    contextRanges: List<IntRange>,
): ParsedAmount? {
    val associations = amounts.mapNotNull { amount ->
        val precedingLabels = labelRanges.filter { label ->
            label.last < amount.range.first && sameClause(text, label, amount.range)
        }
        when {
            precedingLabels.isNotEmpty() -> {
                val distance = precedingLabels.minOf { distanceBetween(it, amount.range) }
                AmountAssociation(amount, 0, distance)
            }
            signalRanges.any { sameClause(text, it, amount.range) } -> {
                val distance = signalRanges.filter { sameClause(text, it, amount.range) }
                    .minOf { distanceBetween(it, amount.range) }
                AmountAssociation(amount, 1, distance)
            }
            contextRanges.any { sameClause(text, it, amount.range) } -> {
                val distance = contextRanges.filter { sameClause(text, it, amount.range) }
                    .minOf { distanceBetween(it, amount.range) }
                AmountAssociation(amount, 2, distance)
            }
            else -> null
        }
    }
    val bestRank = associations.minOfOrNull(AmountAssociation::rank) ?: return null
    val ranked = associations.filter { it.rank == bestRank }
    val bestDistance = ranked.minOf(AmountAssociation::distance)
    return ranked.filter { it.distance == bestDistance }.singleOrNull()?.amount
}

private fun sameClause(text: String, first: IntRange, second: IntRange): Boolean {
    val gap = when {
        first.last < second.first -> text.substring(first.last + 1, second.first)
        second.last < first.first -> text.substring(second.last + 1, first.first)
        else -> ""
    }
    return gap.none { it in CLAUSE_BOUNDARIES }
}

private fun extractMerchant(text: String): String? = MERCHANT_PATTERNS.asSequence()
    .mapNotNull { pattern -> pattern.find(text)?.groupValues?.get(1)?.trim() }
    // 「商户：支付成功」这种把状态词填进商户位的通知真实存在，认出来也别当商户。
    .filter(::isLearnableMerchant)
    .firstOrNull()

private fun String.rangesOf(keyword: String): List<IntRange> {
    if (keyword.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = indexOf(keyword)
    while (start >= 0) {
        ranges += start until start + keyword.length
        start = indexOf(keyword, start + keyword.length)
    }
    return ranges
}

private fun findCnyAmounts(text: String): List<ParsedAmount> = CURRENCY_TOKEN.findAll(text).mapNotNull { token ->
    if (hasAdjacentSignBefore(text, token.range.first)) return@mapNotNull null
    var amountStart = token.range.last + 1
    while (amountStart < text.length && text[amountStart].isWhitespace()) amountStart++
    if (amountStart >= text.length || text[amountStart] in SIGN_MARKERS) return@mapNotNull null

    val match = AMOUNT_AFTER_TOKEN.matchAt(text, amountStart) ?: return@mapNotNull null
    val next = text.getOrNull(match.range.last + 1)
    if (next != null && (next.isDigit() || next == ',' || next == '.' || next in 'A'..'Z' || next in 'a'..'z')) {
        return@mapNotNull null
    }
    val minor = try {
        BigDecimal(match.value.replace(",", ""))
            .movePointRight(2)
            .longValueExact()
            .takeIf { it > 0 }
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    } ?: return@mapNotNull null
    ParsedAmount(minor, match.range)
}.toList()

private fun hasAdjacentSignBefore(text: String, tokenStart: Int): Boolean {
    var index = tokenStart - 1
    while (index >= 0 && text[index].isWhitespace()) index--
    return index >= 0 && text[index] in SIGN_MARKERS
}

private fun distanceBetween(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last
    second.last < first.first -> first.first - second.last
    else -> 0
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private val CLAUSE_BOUNDARIES = setOf('，', ',', '。', '；', ';', '\n')
private const val DEDUPE_BUCKET_SECONDS = 120L
