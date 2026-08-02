package com.local.autobookkeeping.recognition.capture

import android.graphics.Bitmap
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.recognition.AlipayPageParser
import com.local.autobookkeeping.recognition.ExtractionOutcome
import com.local.autobookkeeping.recognition.GenericBillParser
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.PaymentPageParser
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import com.local.autobookkeeping.recognition.WechatPageParser
import com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionController
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class ScreenshotRecognitionController(
    private val textRecognizer: LocalTextRecognizer,
    private val wechatParser: PaymentPageParser = WechatPageParser,
    private val alipayParser: PaymentPageParser = AlipayPageParser,
    private val genericParser: PaymentPageParser = GenericBillParser,
    private val coordinator: RecognitionCoordinator,
) {
    suspend fun process(
        bitmap: Bitmap,
        foregroundPackage: String?,
        observedAt: Instant,
    ): RecognitionOutcome = try {
        val elements = try {
            textRecognizer.recognize(bitmap)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return RecognitionOutcome.Rejected(RecognitionRejectReason.OCR_FAILED)
        }
        val snapshot = PageSnapshot(
            packageName = foregroundPackage,
            elements = elements,
            observedAt = observedAt,
        )
        when (val extracted = parserFor(foregroundPackage).parse(snapshot)) {
            is ExtractionOutcome.Single -> coordinator.process(extracted.candidate)
            is ExtractionOutcome.Multiple -> RecognitionOutcome.NeedsSelection(
                extracted.options.map { RecognitionChoice(candidate = it) },
            )
            is ExtractionOutcome.Rejected -> RecognitionOutcome.Rejected(extracted.reason)
        }
    } finally {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun parserFor(packageName: String?): PaymentPageParser = when (packageName) {
        AutoPageRecognitionController.WECHAT_PACKAGE -> wechatParser
        AutoPageRecognitionController.ALIPAY_PACKAGE -> alipayParser
        else -> genericParser
    }
}
