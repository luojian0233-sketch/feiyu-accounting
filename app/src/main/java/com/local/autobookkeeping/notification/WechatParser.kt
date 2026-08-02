package com.local.autobookkeeping.notification

object WechatParser : PaymentParser {
    override fun parse(notification: NotificationText): PaymentCandidate? {
        if (notification.packageName != WECHAT_PACKAGE) return null
        if (notification.body.contains(REAL_PAID_KEYWORD)) {
            return parseRealPaid(notification)
        }
        return parseSourceCandidate(
            notification = notification,
            expenseKeywords = EXPENSE_KEYWORDS,
            incomeKeywords = INCOME_KEYWORDS,
            refundKeywords = REFUND_KEYWORDS,
            clearConfidence = CLEAR_CONFIDENCE,
            ambiguousConfidence = AMBIGUOUS_CONFIDENCE,
        )
    }

    private fun parseRealPaid(notification: NotificationText): PaymentCandidate? {
        if (notification.title.trim() != REAL_PAYMENT_TITLE) return null
        if (PROMOTION_KEYWORDS.any(notification.body::contains)) return null
        if (CONFLICTING_DIRECTION_KEYWORDS.any(notification.body::contains)) return null
        val amountMinor = parseCnyMinor(notification.body) ?: return null
        return buildCandidate(
            notification = notification,
            amountMinor = amountMinor,
            direction = com.local.autobookkeeping.domain.model.Direction.EXPENSE,
            confidence = AMBIGUOUS_CONFIDENCE,
            merchant = null,
            eventKind = PaymentEventKind.PAYMENT,
        )
    }

    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val CLEAR_CONFIDENCE = 90
    private const val AMBIGUOUS_CONFIDENCE = 40
    private const val REAL_PAYMENT_TITLE = "微信支付"
    private const val REAL_PAID_KEYWORD = "已支付"
    private val EXPENSE_KEYWORDS = setOf("支付成功", "付款成功", "扣款成功")
    private val INCOME_KEYWORDS = setOf("收款到账", "收款成功")
    private val REFUND_KEYWORDS = setOf("退款到账", "退款成功", "退款")
    private val PROMOTION_KEYWORDS = setOf("优惠", "活动", "满减", "优惠券", "最高", "可享")
    private val CONFLICTING_DIRECTION_KEYWORDS = setOf(
        "退款",
        "收款",
        "到账",
        "转账",
        "转入",
        "转出",
        "红包",
    )
}
