package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.PaymentPlatform

object AlipayPageParser : PaymentPageParser {
    override fun parse(snapshot: PageSnapshot): ExtractionOutcome = parsePaymentPage(snapshot, PROFILE)

    private val PROFILE = PageParserProfile(
        requiredPackage = "com.eg.android.AlipayGphone",
        platform = PaymentPlatform.ALIPAY,
        channel = CaptureChannel.ACCESSIBILITY,
        defaultPaymentSignatures = setOf("支付宝", "账单详情", "账单明细"),
        carrySignatureAcrossGroups = true,
        hardRejectSignals = setOf("总资产", "页面加载失败", "加载失败", "网络错误"),
        baseConfidence = 92,
    )
}
