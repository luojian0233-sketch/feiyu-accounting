package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.PaymentPlatform

object WechatPageParser : PaymentPageParser {
    override fun parse(snapshot: PageSnapshot): ExtractionOutcome = parsePaymentPage(snapshot, PROFILE)

    private val PROFILE = PageParserProfile(
        requiredPackage = "com.tencent.mm",
        platform = PaymentPlatform.WECHAT,
        channel = CaptureChannel.ACCESSIBILITY,
        defaultPaymentSignatures = setOf("微信支付", "账单详情"),
        carrySignatureAcrossGroups = true,
        hardRejectSignals = setOf("聊天", "页面加载失败", "加载失败", "网络错误"),
        baseConfidence = 92,
    )
}
