package com.local.autobookkeeping.notification

object AlipayParser : PaymentParser {
    override fun parse(notification: NotificationText): PaymentCandidate? {
        if (notification.packageName != ALIPAY_PACKAGE) return null
        return parseSourceCandidate(
            notification = notification,
            expenseKeywords = EXPENSE_KEYWORDS,
            incomeKeywords = INCOME_KEYWORDS,
            refundKeywords = REFUND_KEYWORDS,
            expenseRedPacketKeywords = EXPENSE_RED_PACKET_KEYWORDS,
            incomeRedPacketKeywords = INCOME_RED_PACKET_KEYWORDS,
            clearConfidence = CLEAR_CONFIDENCE,
            ambiguousConfidence = AMBIGUOUS_CONFIDENCE,
        )
    }

    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    private const val CLEAR_CONFIDENCE = 90
    private const val AMBIGUOUS_CONFIDENCE = 40
    private val EXPENSE_KEYWORDS = setOf("付款成功", "支付成功", "扣款成功", "转账已支付")
    private val INCOME_KEYWORDS = setOf("收款到账", "收款成功", "收益到账", "转账到账")
    private val REFUND_KEYWORDS = setOf("退款到账", "退款成功", "退款")
    private val EXPENSE_RED_PACKET_KEYWORDS = setOf("红包已支付", "发出红包")
    private val INCOME_RED_PACKET_KEYWORDS = setOf("收到红包", "红包到账")
}
