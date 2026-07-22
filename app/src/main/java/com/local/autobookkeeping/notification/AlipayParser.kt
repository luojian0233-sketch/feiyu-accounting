package com.local.autobookkeeping.notification

import com.local.autobookkeeping.domain.model.Direction

object AlipayParser : PaymentParser {
    override fun parse(notification: NotificationText): PaymentCandidate? {
        if (notification.packageName != ALIPAY_PACKAGE) return null
        val text = notification.title + "\n" + notification.body
        val direction = when {
            text.containsAny(EXPENSE_KEYWORDS) -> Direction.EXPENSE
            text.containsAny(INCOME_KEYWORDS) -> Direction.INCOME
            else -> return null
        }
        val amountMinor = parseCnyMinor(text) ?: return null
        return buildCandidate(notification, amountMinor, direction, CONFIDENCE_WITHOUT_MERCHANT)
    }

    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    private const val CONFIDENCE_WITHOUT_MERCHANT = 70
    private val EXPENSE_KEYWORDS = setOf("付款成功", "支付成功", "扣款成功")
    private val INCOME_KEYWORDS = setOf("收款到账", "收款成功", "收益到账")
}
