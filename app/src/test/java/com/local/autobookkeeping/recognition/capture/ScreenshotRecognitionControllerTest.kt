package com.local.autobookkeeping.recognition.capture

import android.graphics.Bitmap
import com.local.autobookkeeping.data.MerchantRuleMatch
import com.local.autobookkeeping.data.RecognitionMatchQuery
import com.local.autobookkeeping.data.RecognitionStore
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.recognition.AutoSavePolicy
import com.local.autobookkeeping.recognition.ExtractionOutcome
import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.PaymentPageParser
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import com.local.autobookkeeping.recognition.TextBounds
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotRecognitionControllerTest {
    private val store = RecordingStore()
    private val coordinator = RecognitionCoordinator(
        store = store,
        autoSavePolicy = AutoSavePolicy { false },
    )
    private val wechatParser = RecordingParser(ExtractionOutcome.Single(candidate("a", PaymentPlatform.WECHAT)))
    private val alipayParser = RecordingParser(ExtractionOutcome.Single(candidate("b", PaymentPlatform.ALIPAY)))
    private val genericParser = RecordingParser(ExtractionOutcome.Single(candidate("c", PaymentPlatform.OTHER)))
    private val recognizedElements = listOf(
        ObservedTextElement("支付成功", TextBounds(17, 31, 219, 68)),
        ObservedTextElement("¥12.34", TextBounds(82, 91, 198, 137)),
    )
    private var recognizeCalls = 0
    private val recognizer = LocalTextRecognizer {
        recognizeCalls += 1
        recognizedElements
    }
    private val controller = ScreenshotRecognitionController(
        textRecognizer = recognizer,
        wechatParser = wechatParser,
        alipayParser = alipayParser,
        genericParser = genericParser,
        coordinator = coordinator,
    )

    @Test
    fun `single result goes through coordinator and bitmap is recycled`() = runTest {
        val bitmap = bitmap()

        val result = controller.process(bitmap, WECHAT_PACKAGE, NOW)

        assertTrue(result is RecognitionOutcome.Saved)
        assertEquals(1, store.createCalls)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `OCR geometry and observation metadata are preserved in page snapshot`() = runTest {
        val bitmap = bitmap()

        controller.process(bitmap, WECHAT_PACKAGE, NOW)

        assertEquals(
            PageSnapshot(WECHAT_PACKAGE, recognizedElements, NOW),
            wechatParser.lastSnapshot,
        )
    }

    @Test
    fun `known platform packages use only their platform parser`() = runTest {
        controller.process(bitmap(), WECHAT_PACKAGE, NOW)
        controller.process(bitmap(), ALIPAY_PACKAGE, NOW)

        assertEquals(1, wechatParser.calls)
        assertEquals(1, alipayParser.calls)
        assertEquals(0, genericParser.calls)
    }

    @Test
    fun `unknown and missing package use generic parser without platform attribution`() = runTest {
        controller.process(bitmap(), "com.example.bank", NOW)
        controller.process(bitmap(), null, NOW)

        assertEquals(2, genericParser.calls)
        assertEquals("com.example.bank", genericParser.snapshots[0].packageName)
        assertEquals(null, genericParser.snapshots[1].packageName)
        assertEquals(0, wechatParser.calls)
        assertEquals(0, alipayParser.calls)
    }

    @Test
    fun `multiple cards return review choices without coordinator persistence`() = runTest {
        val first = candidate("d", PaymentPlatform.OTHER)
        val second = candidate("e", PaymentPlatform.OTHER)
        genericParser.outcome = ExtractionOutcome.Multiple(listOf(first, second))
        val bitmap = bitmap()

        val result = controller.process(bitmap, "com.example.bank", NOW)

        val selection = result as RecognitionOutcome.NeedsSelection
        assertEquals(listOf(first, second), selection.options.map { it.candidate })
        assertEquals(0, store.createCalls)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `parser rejection reason is returned exactly without persistence`() = runTest {
        genericParser.outcome = ExtractionOutcome.Rejected(RecognitionRejectReason.CONFLICTING_DIRECTION)
        val bitmap = bitmap()

        val result = controller.process(bitmap, null, NOW)

        assertEquals(
            RecognitionOutcome.Rejected(RecognitionRejectReason.CONFLICTING_DIRECTION),
            result,
        )
        assertEquals(0, store.createCalls)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `OCR failure is redacted and bitmap is recycled`() = runTest {
        val bitmap = bitmap()
        val failing = ScreenshotRecognitionController(
            textRecognizer = LocalTextRecognizer { error("private OCR contents") },
            wechatParser = wechatParser,
            alipayParser = alipayParser,
            genericParser = genericParser,
            coordinator = coordinator,
        )

        val result = failing.process(bitmap, null, NOW)

        assertEquals(RecognitionOutcome.Rejected(RecognitionRejectReason.OCR_FAILED), result)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `coordinator exception propagates only after bitmap is recycled`() = runTest {
        val bitmap = bitmap()
        store.createFailure = IllegalStateException("private database failure")

        val thrown = runCatching {
            controller.process(bitmap, WECHAT_PACKAGE, NOW)
        }.exceptionOrNull()

        assertSame(store.createFailure, thrown)
        assertTrue(bitmap.isRecycled)
    }

    private class RecordingParser(
        var outcome: ExtractionOutcome,
    ) : PaymentPageParser {
        val snapshots = mutableListOf<PageSnapshot>()
        val calls: Int get() = snapshots.size
        val lastSnapshot: PageSnapshot? get() = snapshots.lastOrNull()

        override fun parse(snapshot: PageSnapshot): ExtractionOutcome {
            snapshots += snapshot
            return outcome
        }
    }

    private class RecordingStore : RecognitionStore {
        var createCalls = 0
        var createFailure: RuntimeException? = null

        override suspend fun findReceipt(digest: String): RecognitionReceipt? = null
        override suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction> = emptyList()
        override suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long {
            createCalls += 1
            createFailure?.let { throw it }
            return 41L
        }
        override suspend fun update(transaction: Transaction, receipt: RecognitionReceipt) = Unit
        override suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt) = Unit
        override suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch? = null
        override suspend fun isCategoryVisible(name: String): Boolean = false
        override suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction? = null
        override suspend fun rememberMerchantRule(merchant: String, category: String, account: String?) = Unit
        override suspend fun confirmAndRemember(transaction: Transaction): Long = transaction.id
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        val NOW: Instant = Instant.parse("2026-08-03T10:15:30Z")

        fun bitmap(): Bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        fun candidate(digestCharacter: String, platform: PaymentPlatform) = RecognitionCandidate(
            platform = platform,
            channel = CaptureChannel.SCREENSHOT,
            eventKind = TransactionEventKind.PAYMENT,
            amountMinor = 1_234,
            direction = Direction.EXPENSE,
            merchant = "测试商户",
            account = null,
            occurredAt = NOW,
            confidence = 70,
            evidenceDigest = digestCharacter.repeat(64),
        )
    }
}
