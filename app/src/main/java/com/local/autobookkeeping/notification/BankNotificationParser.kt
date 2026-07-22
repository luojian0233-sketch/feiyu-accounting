package com.local.autobookkeeping.notification

import com.local.autobookkeeping.domain.model.Direction

class BankNotificationParser(
    enabledPackages: Set<String>,
) : PaymentParser {
    private val enabledPackages = enabledPackages.filterTo(mutableSetOf()) { it.isNotBlank() }

    override fun parse(notification: NotificationText): PaymentCandidate? {
        if (notification.packageName !in enabledPackages) return null
        val text = notification.title + "\n" + notification.body
        if (!text.containsAny(SUPPORTED_MESSAGE_KEYWORDS)) return null
        val amountMinor = parseCnyMinor(text) ?: return null
        val isExpense = text.containsAny(EXPENSE_KEYWORDS)
        val isIncome = text.containsAny(INCOME_KEYWORDS)
        val direction = when {
            isExpense && !isIncome -> Direction.EXPENSE
            isIncome && !isExpense -> Direction.INCOME
            else -> null
        }
        return buildCandidate(notification, amountMinor, direction, CONFIDENCE_WITHOUT_MERCHANT)
    }

    private companion object {
        const val CONFIDENCE_WITHOUT_MERCHANT = 60
        val SUPPORTED_MESSAGE_KEYWORDS = setOf("账户", "账务", "交易", "银行卡", "余额")
        val EXPENSE_KEYWORDS = setOf("消费", "支出", "扣款", "付款", "转出", "debit", "DEBIT")
        val INCOME_KEYWORDS = setOf("收入", "入账", "到账", "收款", "转入", "credit", "CREDIT")
    }
}
