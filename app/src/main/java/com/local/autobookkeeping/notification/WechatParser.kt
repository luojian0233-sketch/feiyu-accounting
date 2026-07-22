package com.local.autobookkeeping.notification

object WechatParser : PaymentParser {
    override fun parse(notification: NotificationText): PaymentCandidate? {
        if (notification.packageName != WECHAT_PACKAGE) return null
        return parseSourceCandidate(
            notification = notification,
            expenseKeywords = EXPENSE_KEYWORDS,
            incomeKeywords = INCOME_KEYWORDS,
            clearConfidence = CLEAR_CONFIDENCE,
            ambiguousConfidence = AMBIGUOUS_CONFIDENCE,
        )
    }

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val CLEAR_CONFIDENCE = 90
    private const val AMBIGUOUS_CONFIDENCE = 40
    private val EXPENSE_KEYWORDS = setOf("支付成功", "付款成功", "扣款成功")
    private val INCOME_KEYWORDS = setOf("收款到账", "收款成功", "退款到账", "退款成功", "退款")
}
