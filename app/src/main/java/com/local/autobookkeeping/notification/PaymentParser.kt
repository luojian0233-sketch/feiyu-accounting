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
)

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

private val CNY_AMOUNT = Regex(
    pattern = "(?:人民币|CNY|RMB|[¥￥])\\s*([0-9]+(?:\\.[0-9]{1,2})?)(?![0-9.,])",
    option = RegexOption.IGNORE_CASE,
)
private val SHA_256_HEX = Regex("[0-9a-f]{64}")
private val DIGIT_RUN = Regex("[0-9]+")
private val WHITESPACE = Regex("\\s+")

internal fun parseCnyMinor(text: String): Long? {
    val amount = CNY_AMOUNT.find(text)?.groupValues?.get(1) ?: return null
    return try {
        BigDecimal(amount)
            .movePointRight(2)
            .longValueExact()
            .takeIf { it > 0 }
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}

internal fun buildCandidate(
    notification: NotificationText,
    amountMinor: Long,
    direction: Direction?,
    confidence: Int,
): PaymentCandidate {
    val redactedTitle = notification.title
        .lowercase(Locale.ROOT)
        .trim()
        .replace(DIGIT_RUN, "#")
        .replace(WHITESPACE, " ")
    val bucket = notification.postedAt.epochSecond / DEDUPE_BUCKET_SECONDS
    val digestInput = listOf(
        notification.packageName,
        amountMinor.toString(),
        direction?.name ?: "UNKNOWN",
        redactedTitle,
        bucket.toString(),
    ).joinToString("|")
    val dedupeInput = listOf(
        notification.packageName,
        amountMinor.toString(),
        redactedTitle,
        bucket.toString(),
    ).joinToString("|")
    return PaymentCandidate(
        amountMinor = amountMinor,
        direction = direction,
        merchant = null,
        occurredAt = notification.postedAt,
        sourcePackage = notification.packageName,
        confidence = confidence,
        redactedDigest = digestInput.sha256(),
        dedupeKey = dedupeInput.sha256(),
    )
}

internal fun String.containsAny(keywords: Set<String>): Boolean = keywords.any(::contains)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val DEDUPE_BUCKET_SECONDS = 120L
