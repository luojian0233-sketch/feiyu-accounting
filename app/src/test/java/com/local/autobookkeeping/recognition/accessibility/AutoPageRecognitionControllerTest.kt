package com.local.autobookkeeping.recognition.accessibility

import android.graphics.Bitmap
import com.local.autobookkeeping.data.NotificationSourceSettings
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.recognition.InMemoryRecognitionDiagnostics
import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.TextBounds
import com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGateway
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.LocalTextRecognizer
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoPageRecognitionControllerTest {
    private val gateway = AccessibilityCaptureGateway()
    private val processor = RecordingProcessor()
    private val diagnostics = InMemoryRecognitionDiagnostics()
    private var ocrCalls = 0
    private var settingsCalls = 0
    private var enabled = true
    private val recognizer = LocalTextRecognizer {
        ocrCalls += 1
        completeWechatElements()
    }
    private val controller = controller(recognizer)

    @Test
    fun `controller ignores packages outside WeChat and Alipay`() = runTest {
        var screenshotCalls = 0
        gateway.attach {
            screenshotCalls += 1
            error("capture must stay outside package scope")
        }

        controller.onWindowChanged(page("com.example.bank", "支付成功", "¥1.00"))

        assertEquals(0, settingsCalls)
        assertEquals(0, processor.calls)
        assertEquals(0, screenshotCalls)
    }

    @Test
    fun `incomplete signed payment page uses one screenshot fallback`() = runTest {
        var screenshotCalls = 0
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        gateway.attach {
            screenshotCalls += 1
            CaptureResult.Success(bitmap, WECHAT_PACKAGE)
        }

        controller.onWindowChanged(page(WECHAT_PACKAGE, "微信支付", "账单详情"))

        assertEquals(1, screenshotCalls)
        assertEquals(1, ocrCalls)
        assertEquals(1, processor.calls)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `same normalized page inside debounce window is processed once`() = runTest {
        repeat(3) { controller.onWindowChanged(completeWechatPage()) }

        assertEquals(1, processor.calls)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `concurrent same signed page has only one in flight processor`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blockingProcessor = object : RecognitionCandidateProcessor {
            var calls = 0
            override suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome {
                calls += 1
                entered.complete(Unit)
                release.await()
                return RecognitionOutcome.Duplicate
            }
        }
        val blockingController = controller(recognizer, candidateProcessor = blockingProcessor)

        val first = async { blockingController.onWindowChanged(completeWechatPage()) }
        entered.await()
        val second = async { blockingController.onWindowChanged(completeWechatPage()) }
        runCurrent()

        assertEquals(1, blockingProcessor.calls)
        release.complete(Unit)
        first.await()
        second.await()
    }

    @Test
    fun `coordinator exception releases page for immediate retry`() = runTest {
        val flakyProcessor = object : RecognitionCandidateProcessor {
            var calls = 0
            override suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome {
                calls += 1
                if (calls == 1) error("transient store failure")
                return RecognitionOutcome.Duplicate
            }
        }
        val flakyController = controller(recognizer, candidateProcessor = flakyProcessor)

        try {
            flakyController.onWindowChanged(completeWechatPage())
        } catch (_: IllegalStateException) {
            Unit
        }
        flakyController.onWindowChanged(completeWechatPage())

        assertEquals(2, flakyProcessor.calls)
    }

    @Test
    fun `coordinator cancellation propagates and releases page for retry`() = runTest {
        val cancellingProcessor = object : RecognitionCandidateProcessor {
            var calls = 0
            override suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome {
                calls += 1
                if (calls == 1) throw CancellationException("cancel")
                return RecognitionOutcome.Duplicate
            }
        }
        val cancellingController = controller(recognizer, candidateProcessor = cancellingProcessor)

        val thrown = try {
            cancellingController.onWindowChanged(completeWechatPage())
            null
        } catch (failure: Throwable) {
            failure
        }
        cancellingController.onWindowChanged(completeWechatPage())

        assertTrue(thrown is CancellationException)
        assertEquals(2, cancellingProcessor.calls)
    }

    @Test
    fun `temporary capture failure releases page for immediate retry`() = runTest {
        var screenshotCalls = 0
        gateway.attach {
            screenshotCalls += 1
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }

        repeat(2) {
            controller.onWindowChanged(page(WECHAT_PACKAGE, "微信支付", "账单详情"))
        }

        assertEquals(2, screenshotCalls)
    }

    @Test
    fun `complete Alipay page is processed without screenshot`() = runTest {
        var screenshotCalls = 0
        gateway.attach {
            screenshotCalls += 1
            error("complete pages must not be captured")
        }

        controller.onWindowChanged(page(ALIPAY_PACKAGE, "支付宝", "支付成功", "¥1.00"))

        assertEquals(1, processor.calls)
        assertEquals(0, screenshotCalls)
    }

    @Test
    fun `unsigned rejected page never invokes screenshot fallback`() = runTest {
        var screenshotCalls = 0
        gateway.attach {
            screenshotCalls += 1
            error("unsigned pages must not be captured")
        }

        controller.onWindowChanged(page(WECHAT_PACKAGE, "发现", "朋友圈"))

        assertEquals(0, screenshotCalls)
        assertEquals(0, processor.calls)
    }

    @Test
    fun `unsigned page does not occupy debounce storage`() = runTest {
        val debouncer = RecognitionDebouncer(windowMillis = 1_000, maxEntries = 1)
        val unsigned = page(WECHAT_PACKAGE, "发现", "朋友圈")
        val unsignedController = controller(recognizer, debouncer = debouncer)

        unsignedController.onWindowChanged(unsigned)

        assertTrue(debouncer.reserve(unsigned, 10_000) != null)
    }

    @Test
    fun `signed conflicting page commits a deterministic debounce terminal`() = runTest {
        val debouncer = RecognitionDebouncer(windowMillis = 1_000, maxEntries = 1)
        val conflicting = page(WECHAT_PACKAGE, "微信支付", "支付成功 退款成功 ¥1.00")
        val conflictingController = controller(recognizer, debouncer = debouncer)

        conflictingController.onWindowChanged(conflicting)

        assertTrue(debouncer.reserve(conflicting, 10_000) == null)
        assertEquals(0, processor.calls)
    }

    @Test
    fun `disabled platform is fail closed before parsing or capture`() = runTest {
        enabled = false
        var screenshotCalls = 0
        gateway.attach {
            screenshotCalls += 1
            error("disabled platform must not be captured")
        }

        controller.onWindowChanged(page(WECHAT_PACKAGE, "微信支付", "账单详情"))

        assertEquals(1, settingsCalls)
        assertEquals(0, screenshotCalls)
        assertEquals(0, processor.calls)
    }

    @Test
    fun `repository adapter requires master and matching platform source`() = runTest {
        val settings = FakeSourceSettings(automatic = false, wechat = true, alipay = true)
        val adapter = SettingsAutomaticPageRecognitionSettings(settings)

        assertTrue(!adapter.isEnabled(WECHAT_PACKAGE))
        settings.autoBookkeepingEnabled.value = true
        assertTrue(adapter.isEnabled(WECHAT_PACKAGE))
        settings.wechatEnabled.value = false
        assertTrue(!adapter.isEnabled(WECHAT_PACKAGE))
        assertTrue(adapter.isEnabled(ALIPAY_PACKAGE))
        assertTrue(!adapter.isEnabled("com.example.bank"))
    }

    @Test
    fun `settings read failure is fail closed and cancellation still propagates`() = runTest {
        val failureController = controller(
            recognizer = recognizer,
            settings = AutomaticPageRecognitionSettings { throw IllegalStateException("raw secret") },
        )

        failureController.onWindowChanged(completeWechatPage())

        assertEquals(0, processor.calls)

        val cancelledController = controller(
            recognizer = recognizer,
            settings = AutomaticPageRecognitionSettings { throw CancellationException("cancel") },
        )
        val thrown = try {
            cancelledController.onWindowChanged(completeWechatPage())
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `mismatched foreground package rejects OCR and still recycles bitmap`() = runTest {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        gateway.attach { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) }

        controller.onWindowChanged(page(WECHAT_PACKAGE, "微信支付", "账单详情"))

        assertEquals(0, ocrCalls)
        assertEquals(0, processor.calls)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `OCR exception releases page for retry and every captured bitmap is recycled`() = runTest {
        val bitmaps = mutableListOf<Bitmap>()
        gateway.attach {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmaps += bitmap
                CaptureResult.Success(bitmap, WECHAT_PACKAGE)
            }
        }
        val throwingController = controller(LocalTextRecognizer { error("private page text") })

        repeat(2) {
            throwingController.onWindowChanged(page(WECHAT_PACKAGE, "微信支付", "账单详情"))
        }

        assertEquals(0, processor.calls)
        assertEquals(2, bitmaps.size)
        assertTrue(bitmaps.all(Bitmap::isRecycled))
        assertEquals(
            com.local.autobookkeeping.recognition.RecognitionDiagnostic.OCR_FAILED,
            diagnostics.state.value,
        )
    }

    @Test
    fun `OCR conflicting direction is a deterministic debounce terminal`() = runTest {
        var screenshotCalls = 0
        gateway.attach {
            screenshotCalls += 1
            CaptureResult.Success(
                Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888),
                WECHAT_PACKAGE,
            )
        }
        val conflictingRecognizer = LocalTextRecognizer {
            listOf(
                element("微信支付", 0),
                element("支付成功 退款成功 ¥1.00", 40),
            )
        }
        val conflictingController = controller(conflictingRecognizer)

        repeat(2) {
            conflictingController.onWindowChanged(page(WECHAT_PACKAGE, "微信支付", "账单详情"))
        }

        assertEquals(1, screenshotCalls)
        assertEquals(0, processor.calls)
    }

    private fun controller(
        recognizer: LocalTextRecognizer,
        settings: AutomaticPageRecognitionSettings = AutomaticPageRecognitionSettings {
            settingsCalls += 1
            enabled
        },
        candidateProcessor: RecognitionCandidateProcessor = processor,
        debouncer: RecognitionDebouncer = RecognitionDebouncer(windowMillis = 1_000),
    ) = AutoPageRecognitionController(
        settings = settings,
        captureGateway = gateway,
        textRecognizer = recognizer,
        candidateProcessor = candidateProcessor,
        diagnostics = diagnostics,
        debouncer = debouncer,
        elapsedRealtimeMillis = { 10_000L },
    )

    private class RecordingProcessor : RecognitionCandidateProcessor {
        var calls = 0
        override suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome {
            calls += 1
            return RecognitionOutcome.Duplicate
        }
    }

    private class FakeSourceSettings(
        automatic: Boolean,
        wechat: Boolean,
        alipay: Boolean,
    ) : NotificationSourceSettings {
        override val autoBookkeepingEnabled = MutableStateFlow(automatic)
        override val wechatEnabled = MutableStateFlow(wechat)
        override val alipayEnabled = MutableStateFlow(alipay)
        override val enabledBankPackages = MutableStateFlow<Set<String>>(emptySet())
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        val NOW: Instant = Instant.parse("2026-07-29T10:00:00Z")

        fun completeWechatPage() = PageSnapshot(WECHAT_PACKAGE, completeWechatElements(), NOW)

        fun completeWechatElements() = listOf(
            element("微信支付", 0),
            element("支付成功", 40),
            element("¥1.00", 80),
        )

        fun page(packageName: String, vararg texts: String) = PageSnapshot(
            packageName = packageName,
            elements = texts.mapIndexed { index, text -> element(text, index * 40) },
            observedAt = NOW,
        )

        fun element(text: String, top: Int) = ObservedTextElement(
            text = text,
            bounds = TextBounds(0, top, 200, top + 30),
        )
    }
}
