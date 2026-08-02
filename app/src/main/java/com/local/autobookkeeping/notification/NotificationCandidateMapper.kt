package com.local.autobookkeeping.notification

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.TransactionEventKind

fun PaymentCandidate.toRecognitionCandidate(): RecognitionCandidate = RecognitionCandidate(
    platform = when (sourcePackage) {
        NotificationRouter.WECHAT_PACKAGE -> PaymentPlatform.WECHAT
        NotificationRouter.ALIPAY_PACKAGE -> PaymentPlatform.ALIPAY
        else -> PaymentPlatform.OTHER
    },
    channel = CaptureChannel.NOTIFICATION,
    eventKind = eventKind.toTransactionEventKind(),
    amountMinor = amountMinor,
    direction = direction,
    merchant = merchant,
    account = null,
    occurredAt = occurredAt,
    confidence = confidence,
    evidenceDigest = redactedDigest,
)

internal fun PaymentEventKind.toTransactionEventKind(): TransactionEventKind = when (this) {
    PaymentEventKind.PAYMENT,
    PaymentEventKind.DEBIT,
    -> TransactionEventKind.PAYMENT
    PaymentEventKind.RECEIPT,
    PaymentEventKind.CREDIT,
    -> TransactionEventKind.RECEIPT
    PaymentEventKind.REFUND -> TransactionEventKind.REFUND
    PaymentEventKind.TRANSFER -> TransactionEventKind.TRANSFER
    PaymentEventKind.RED_PACKET -> TransactionEventKind.RED_PACKET
    PaymentEventKind.AMBIGUOUS -> TransactionEventKind.UNKNOWN
}
