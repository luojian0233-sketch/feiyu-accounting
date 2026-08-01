package com.local.autobookkeeping.domain.model

import java.time.Instant

enum class Direction {
    INCOME,
    EXPENSE,
}

enum class Source {
    MANUAL,
    NOTIFICATION,
    PAGE_RECOGNITION,
    SCREENSHOT,
}

data class Transaction(
    val id: Long = 0,
    val amountMinor: Long,
    val currency: String = "CNY",
    val direction: Direction,
    val category: String,
    val occurredAt: Instant,
    val merchant: String = "",
    val note: String = "",
    val account: String = "",
    val source: Source = Source.MANUAL,
    val pendingConfirmation: Boolean = false,
    val internalTransfer: Boolean = false,
    val dedupeKey: String? = null,
    val refundOfId: Long? = null,
    val platform: PaymentPlatform = PaymentPlatform.UNKNOWN,
    val eventKind: TransactionEventKind = TransactionEventKind.UNKNOWN,
    val recognitionConfidence: Int = 100,
) {
    init {
        require(amountMinor > 0)
        require(currency.matches(Regex("[A-Z]{3}")))
        require(category.isNotBlank())
        require(recognitionConfidence in 0..100)
    }

    override fun toString(): String =
        "Transaction(" +
            "id=$id, " +
            "amountMinor=$amountMinor, " +
            "currency=$currency, " +
            "direction=$direction, " +
            "category=$category, " +
            "occurredAt=$occurredAt, " +
            "merchant=<redacted>, " +
            "note=<redacted>, " +
            "source=$source, " +
            "platform=$platform, " +
            "eventKind=$eventKind, " +
            "recognitionConfidence=$recognitionConfidence, " +
            "pendingConfirmation=$pendingConfirmation, " +
            "internalTransfer=$internalTransfer)"
}
