package com.local.autobookkeeping.recognition

import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.PaymentPlatform

object GenericBillParser : PaymentPageParser {
    override fun parse(snapshot: PageSnapshot): ExtractionOutcome = parsePaymentPage(snapshot, PROFILE)

    private val PROFILE = PageParserProfile(
        requiredPackage = null,
        platform = PaymentPlatform.OTHER,
        channel = CaptureChannel.SCREENSHOT,
        defaultPaymentSignatures = setOf("账单详情", "账单明细", "交易详情"),
        carrySignatureAcrossGroups = false,
        hardRejectSignals = setOf("优惠券", "总资产", "页面加载失败", "加载失败", "网络错误"),
        baseConfidence = 72,
    )
}
