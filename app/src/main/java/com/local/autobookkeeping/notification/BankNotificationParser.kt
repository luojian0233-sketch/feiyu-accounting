package com.local.autobookkeeping.notification

class BankNotificationParser(
    enabledPackages: Set<String>,
) : PaymentParser {
    private val enabledPackages = enabledPackages.filterTo(mutableSetOf()) { it.isNotBlank() }

    override fun parse(notification: NotificationText): PaymentCandidate? {
        if (notification.packageName !in enabledPackages) return null
        return parseSourceCandidate(
            notification = notification,
            expenseKeywords = EXPENSE_KEYWORDS,
            incomeKeywords = INCOME_KEYWORDS,
            fallbackContextKeywords = TRANSACTION_CONTEXT_KEYWORDS,
            expenseEventKind = PaymentEventKind.DEBIT,
            incomeEventKind = PaymentEventKind.CREDIT,
            refundKeywords = REFUND_KEYWORDS,
            clearConfidence = CLEAR_CONFIDENCE,
            ambiguousConfidence = AMBIGUOUS_CONFIDENCE,
        )
    }

    private companion object {
        const val CLEAR_CONFIDENCE = 90
        const val AMBIGUOUS_CONFIDENCE = 40
        val TRANSACTION_CONTEXT_KEYWORDS = setOf(
            "账户",
            "账务",
            "交易",
            "银行卡",
            "余额",
            "account",
            "transaction",
            "balance",
        )
        val EXPENSE_KEYWORDS = setOf("消费", "支出", "扣款", "付款", "借记", "debit")
        val INCOME_KEYWORDS = setOf("收入", "入账", "到账", "收款", "贷记", "credit")
        val REFUND_KEYWORDS = setOf("退款")
    }
}
