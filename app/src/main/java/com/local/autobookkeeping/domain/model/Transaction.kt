package com.local.autobookkeeping.domain.model

import java.time.Instant

enum class Direction {
    INCOME,
    EXPENSE,
}

enum class Source {
    MANUAL,
    NOTIFICATION,
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
) {
    init {
        require(amountMinor > 0)
        require(currency.matches(Regex("[A-Z]{3}")))
        require(category.isNotBlank())
    }
}
