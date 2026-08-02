package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.recognition.AlipayPageParser
import com.local.autobookkeeping.recognition.ExtractionOutcome
import com.local.autobookkeeping.recognition.InMemoryRecognitionDiagnostics
import com.local.autobookkeeping.recognition.PageSnapshot
import com.local.autobookkeeping.recognition.PaymentPageParser
import com.local.autobookkeeping.recognition.RecognitionCoordinator
import com.local.autobookkeeping.recognition.RecognitionDiagnostic
import com.local.autobookkeeping.recognition.RecognitionDiagnostics
import com.local.autobookkeeping.recognition.WechatPageParser
import com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGateway
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.LocalTextRecognizer
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

fun interface AutomaticPageRecognitionSettings {
    suspend fun isEnabled(packageName: String): Boolean
}

internal fun interface RecognitionCandidateProcessor {
    suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome
}

class RecognitionDebouncer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val lock = Any()
    private val lastSeenByDigest = mutableMapOf<String, Long>()

    init {
        require(windowMillis >= 0)
    }

    fun shouldProcess(snapshot: PageSnapshot, nowMillis: Long): Boolean {
        val digest = snapshot.normalizedDigest()
        return synchronized(lock) {
            val previous = lastSeenByDigest[digest]
            if (previous != null && nowMillis >= previous && nowMillis - previous < windowMillis) {
                false
            } else {
                lastSeenByDigest[digest] = nowMillis
                true
            }
        }
    }

    private fun PageSnapshot.normalizedDigest(): String {
        val normalized = buildString {
            append(packageName.orEmpty())
            append('\n')
            elements
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.bounds.bottom }, { it.bounds.right }))
                .forEach { element ->
                    append(element.text.lowercase().replace(WHITESPACE, ""))
                    append('|')
                    append(element.bounds.left)
                    append(',')
                    append(element.bounds.top)
                    append(',')
                    append(element.bounds.right)
                    append(',')
                    append(element.bounds.bottom)
                    append('\n')
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_000L
        val WHITESPACE = Regex("\\s+")
    }
}

class AutoPageRecognitionController internal constructor(
    private val settings: AutomaticPageRecognitionSettings,
    private val captureGateway: AccessibilityCaptureGateway,
    private val textRecognizer: LocalTextRecognizer,
    private val candidateProcessor: RecognitionCandidateProcessor,
    private val diagnostics: RecognitionDiagnostics,
    private val debouncer: RecognitionDebouncer,
    private val elapsedRealtimeMillis: () -> Long,
) {
    constructor(
        settings: AutomaticPageRecognitionSettings,
        captureGateway: AccessibilityCaptureGateway,
        textRecognizer: LocalTextRecognizer,
        coordinator: RecognitionCoordinator,
        diagnostics: RecognitionDiagnostics = InMemoryRecognitionDiagnostics(),
        debouncer: RecognitionDebouncer = RecognitionDebouncer(),
        elapsedRealtimeMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
    ) : this(
        settings = settings,
        captureGateway = captureGateway,
        textRecognizer = textRecognizer,
        candidateProcessor = RecognitionCandidateProcessor(coordinator::process),
        diagnostics = diagnostics,
        debouncer = debouncer,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    suspend fun onWindowChanged(snapshot: PageSnapshot) {
        val parser = parserFor(snapshot.packageName) ?: return
        if (!isEnabled(snapshot.packageName.orEmpty())) return
        if (!debouncer.shouldProcess(snapshot, elapsedRealtimeMillis())) return

        when (val parsed = parser.parse(snapshot)) {
            is ExtractionOutcome.Single -> candidateProcessor.process(parsed.candidate)
            is ExtractionOutcome.Multiple -> parsed.options.forEach { candidateProcessor.process(it) }
            is ExtractionOutcome.Rejected -> if (
                parsed.reason == RecognitionRejectReason.INCOMPLETE_FIELDS
            ) {
                processSingleOcrFallback(snapshot, parser)
            }
        }
    }

    private suspend fun isEnabled(packageName: String): Boolean = try {
        settings.isEnabled(packageName)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private suspend fun processSingleOcrFallback(
        original: PageSnapshot,
        parser: PaymentPageParser,
    ) {
        when (val captured = captureGateway.capture()) {
            is CaptureResult.Success -> processCapturedBitmap(captured, original, parser)
            is CaptureResult.Failure -> diagnostics.record(captured.reason.toDiagnostic())
            CaptureResult.RequiresSystemConsent -> diagnostics.record(RecognitionDiagnostic.PERMISSION_MISSING)
        }
    }

    private suspend fun processCapturedBitmap(
        captured: CaptureResult.Success,
        original: PageSnapshot,
        parser: PaymentPageParser,
    ) {
        val bitmap = captured.bitmap
        try {
            if (captured.foregroundPackage != original.packageName) {
                diagnostics.record(RecognitionDiagnostic.CAPTURE_FAILED)
                return
            }
            val elements = try {
                textRecognizer.recognize(bitmap)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                diagnostics.record(RecognitionDiagnostic.OCR_FAILED)
                return
            }
            val ocrSnapshot = PageSnapshot(
                packageName = original.packageName,
                elements = elements,
                observedAt = original.observedAt,
            )
            when (val parsed = parser.parse(ocrSnapshot)) {
                is ExtractionOutcome.Single -> candidateProcessor.process(parsed.candidate)
                is ExtractionOutcome.Multiple -> parsed.options.forEach { candidateProcessor.process(it) }
                is ExtractionOutcome.Rejected -> Unit
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun parserFor(packageName: String?): PaymentPageParser? = when (packageName) {
        WECHAT_PACKAGE -> WechatPageParser
        ALIPAY_PACKAGE -> AlipayPageParser
        else -> null
    }

    private fun CaptureFailure.toDiagnostic(): RecognitionDiagnostic = when (this) {
        CaptureFailure.NO_ACCESSIBILITY_ACCESS -> RecognitionDiagnostic.PERMISSION_MISSING
        else -> RecognitionDiagnostic.CAPTURE_FAILED
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    }
}
