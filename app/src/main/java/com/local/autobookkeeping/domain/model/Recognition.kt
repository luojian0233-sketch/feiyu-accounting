package com.local.autobookkeeping.domain.model

import java.time.Instant

enum class PaymentPlatform {
    WECHAT,
    ALIPAY,
    OTHER,
    UNKNOWN,
}

enum class CaptureChannel {
    NOTIFICATION,
    ACCESSIBILITY,
    SCREENSHOT,
}

enum class TransactionEventKind {
    PAYMENT,
    RECEIPT,
    REFUND,
    TRANSFER,
    RED_PACKET,
    UNKNOWN,
}

enum class RecognitionRejectReason {
    NOT_PAYMENT_PAGE,
    INCOMPLETE_FIELDS,
    CONFLICTING_DIRECTION,
    SECURE_WINDOW,
    CAPTURE_FAILED,
    OCR_FAILED,
    PERMISSION_MISSING,
}

data class RecognitionCandidate(
    val platform: PaymentPlatform,
    val channel: CaptureChannel,
    val eventKind: TransactionEventKind,
    val amountMinor: Long,
    val direction: Direction?,
    val merchant: String?,
    val account: String?,
    val occurredAt: Instant,
    val confidence: Int,
    val evidenceDigest: String,
) {
    init {
        require(amountMinor > 0)
        require(confidence in 0..100)
        require(evidenceDigest.matches(SHA256_DIGEST))
    }
}

data class RecognitionReceipt(
    val transactionId: Long = 0,
    val channel: CaptureChannel,
    val platform: PaymentPlatform,
    val eventKind: TransactionEventKind,
    val observedAt: Instant,
    val digest: String,
) {
    init {
        require(digest.matches(SHA256_DIGEST))
    }
}

data class RecognitionChoice(
    val candidate: RecognitionCandidate,
    val matchTransactionId: Long? = null,
)

sealed interface RecognitionOutcome {
    data class Saved(val transactionId: Long, val pending: Boolean) : RecognitionOutcome
    data class NeedsSelection(val options: List<RecognitionChoice>) : RecognitionOutcome
    data object Duplicate : RecognitionOutcome
    data class Rejected(val reason: RecognitionRejectReason) : RecognitionOutcome
}

private val SHA256_DIGEST = Regex("[0-9a-f]{64}")
