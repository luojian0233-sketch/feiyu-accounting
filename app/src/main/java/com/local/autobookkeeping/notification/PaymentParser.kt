package com.local.autobookkeeping.notification

import com.local.autobookkeeping.domain.model.Direction
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
) {
    init {
        require(amountMinor > 0)
        require(sourcePackage.isNotBlank())
        require(confidence in 0..100)
        require(redactedDigest.matches(SHA_256_HEX))
        require(dedupeKey.matches(SHA_256_HEX))
    }
}

fun interface PaymentParser {
    fun parse(notification: NotificationText): PaymentCandidate?
}

private data class KeywordSignal(
    val direction: Direction,
    val range: IntRange,
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

internal fun parseCnyMinor(text: String): Long? = findCnyAmounts(text).singleOrNull()?.minor

internal fun parseSourceCandidate(
    notification: NotificationText,
    expenseKeywords: Set<String>,
    incomeKeywords: Set<String>,
    fallbackContextKeywords: Set<String> = emptySet(),
    clearConfidence: Int,
    ambiguousConfidence: Int,
): PaymentCandidate? {
    val text = notification.title + "\n" + notification.body
    val normalized = text.lowercase(Locale.ROOT)
    if (normalized.containsAny(PROMOTION_KEYWORDS)) return null

    val signals = findSignals(normalized, expenseKeywords, Direction.EXPENSE) +
        findSignals(normalized, incomeKeywords, Direction.INCOME)
    val contextRanges = fallbackContextKeywords.flatMap { keyword ->
        normalized.rangesOf(keyword.lowercase(Locale.ROOT))
    }
    if (signals.isEmpty() && contextRanges.isEmpty()) return null

    val amounts = findCnyAmounts(text)
    if (amounts.isEmpty()) return null
    val anchors = signals.map(KeywordSignal::range).ifEmpty { contextRanges }
    val amount = amounts.minByOrNull { candidate ->
        anchors.minOf { anchor -> contextualDistance(text, candidate.range, anchor) }
    } ?: return null
    val directions = signals.map(KeywordSignal::direction).toSet()
    val direction = directions.singleOrNull()
    val confidence = if (direction == null) ambiguousConfidence else clearConfidence
    return buildCandidate(
        notification = notification,
        amountMinor = amount.minor,
        direction = direction,
        confidence = confidence,
    )
}

internal fun buildCandidate(
    notification: NotificationText,
    amountMinor: Long,
    direction: Direction?,
    confidence: Int,
): PaymentCandidate {
    val bucket = notification.postedAt.epochSecond / DEDUPE_BUCKET_SECONDS
    val eventSignal = direction?.name ?: AMBIGUOUS_EVENT_SIGNAL
    val signalFields = listOf(
        notification.packageName,
        amountMinor.toString(),
        eventSignal,
        bucket.toString(),
    ).joinToString("|")
    return PaymentCandidate(
        amountMinor = amountMinor,
        direction = direction,
        merchant = null,
        occurredAt = notification.postedAt,
        sourcePackage = notification.packageName,
        confidence = confidence,
        redactedDigest = "digest-v1|$signalFields".sha256(),
        dedupeKey = "dedupe-v1|$signalFields".sha256(),
    )
}

private fun String.containsAny(keywords: Set<String>): Boolean = keywords.any(::contains)

private fun findSignals(
    normalizedText: String,
    keywords: Set<String>,
    direction: Direction,
): List<KeywordSignal> = keywords.flatMap { keyword ->
    normalizedText.rangesOf(keyword.lowercase(Locale.ROOT)).map { range -> KeywordSignal(direction, range) }
}

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
    if (amountStart >= text.length || text[amountStart] == '+' || text[amountStart] == '-') return@mapNotNull null

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
    return index >= 0 && (text[index] == '+' || text[index] == '-')
}

private fun distanceBetween(first: IntRange, second: IntRange): Int = when {
    first.last < second.first -> second.first - first.last
    second.last < first.first -> first.first - second.last
    else -> 0
}

private fun contextualDistance(text: String, first: IntRange, second: IntRange): Int {
    val gap = when {
        first.last < second.first -> text.substring(first.last + 1, second.first)
        second.last < first.first -> text.substring(second.last + 1, first.first)
        else -> ""
    }
    val clauseBoundaryPenalty = gap.count { it in CLAUSE_BOUNDARIES } * CLAUSE_BOUNDARY_PENALTY
    return distanceBetween(first, second) + clauseBoundaryPenalty
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val AMBIGUOUS_EVENT_SIGNAL = "AMBIGUOUS"
private const val CLAUSE_BOUNDARY_PENALTY = 1_000
private val CLAUSE_BOUNDARIES = setOf('，', ',', '。', '；', ';', '\n')
private const val DEDUPE_BUCKET_SECONDS = 120L
