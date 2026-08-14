package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.data.NotificationSourceSettings
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
import kotlinx.coroutines.flow.first

fun interface AutomaticPageRecognitionSettings {
    suspend fun isEnabled(packageName: String): Boolean
}

class SettingsAutomaticPageRecognitionSettings(
    private val settings: NotificationSourceSettings,
) : AutomaticPageRecognitionSettings {
    override suspend fun isEnabled(packageName: String): Boolean {
        if (!settings.autoBookkeepingEnabled.first()) return false
        return when (packageName) {
            AutoPageRecognitionController.WECHAT_PACKAGE -> settings.wechatEnabled.first()
            AutoPageRecognitionController.ALIPAY_PACKAGE -> settings.alipayEnabled.first()
            else -> false
        }
    }
}

internal fun interface RecognitionCandidateProcessor {
    suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome
}

class RecognitionDebouncer(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private var nextToken = 0L

    init {
        require(windowMillis >= 0)
        require(maxEntries > 0)
    }

    class Reservation internal constructor(
        internal val digest: String,
        internal val token: Long,
    )

    fun reserve(snapshot: PageSnapshot, nowMillis: Long): Reservation? {
        val digest = snapshot.normalizedDigest()
        return synchronized(lock) {
            removeExpiredCommitted(nowMillis)
            if (digest in entries) return@synchronized null
            if (entries.size >= maxEntries) {
                entries.entries
                    .filter { it.value.committed }
                    .minByOrNull { it.value.recordedAtMillis }
                    ?.key
                    ?.let(entries::remove)
            }
            if (entries.size >= maxEntries) return@synchronized null
            val token = ++nextToken
            entries[digest] = Entry(token, nowMillis, committed = false)
            Reservation(digest, token)
        }
    }

    fun commit(reservation: Reservation, nowMillis: Long) {
        synchronized(lock) {
            val current = entries[reservation.digest]
            if (current?.token == reservation.token) {
                entries[reservation.digest] = current.copy(
                    recordedAtMillis = nowMillis,
                    committed = true,
                )
            }
        }
    }

    fun release(reservation: Reservation) {
        synchronized(lock) {
            if (entries[reservation.digest]?.token == reservation.token) {
                entries.remove(reservation.digest)
            }
        }
    }

    private fun removeExpiredCommitted(nowMillis: Long) {
        entries.entries.removeAll { (_, entry) ->
            entry.committed &&
                nowMillis >= entry.recordedAtMillis &&
                nowMillis - entry.recordedAtMillis >= windowMillis
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
        const val DEFAULT_MAX_ENTRIES = 128
        val WHITESPACE = Regex("\\s+")
    }

    private data class Entry(
        val token: Long,
        val recordedAtMillis: Long,
        val committed: Boolean,
    )
}

class AutoPageRecognitionController internal constructor(
    private val settings: AutomaticPageRecognitionSettings,
    private val captureGateway: AccessibilityCaptureGateway,
    private val textRecognizer: LocalTextRecognizer,
    private val candidateProcessor: RecognitionCandidateProcessor,
    private val diagnostics: RecognitionDiagnostics,
    private val debouncer: RecognitionDebouncer,
    private val opaquePageDebouncer: RecognitionDebouncer = RecognitionDebouncer(
        windowMillis = OPAQUE_PAGE_WINDOW_MILLIS,
        maxEntries = OPAQUE_PAGE_MAX_ENTRIES,
    ),
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
        val parsed = parser.parse(snapshot)
        if (
            parsed is ExtractionOutcome.Rejected &&
            parsed.reason == RecognitionRejectReason.NOT_PAYMENT_PAGE
        ) {
            if (snapshot.elements.isEmpty()) {
                processOpaquePageFallback(snapshot, parser)
            }
            return
        }
        val reservation = debouncer.reserve(snapshot, elapsedRealtimeMillis()) ?: return
        var terminal = false
        try {
            terminal = processSignedPage(parsed, snapshot, parser)
        } finally {
            if (terminal) {
                debouncer.commit(reservation, elapsedRealtimeMillis())
            } else {
                debouncer.release(reservation)
            }
        }
    }

    private suspend fun processOpaquePageFallback(
        snapshot: PageSnapshot,
        parser: PaymentPageParser,
    ) {
        val reservation = opaquePageDebouncer.reserve(
            snapshot,
            elapsedRealtimeMillis(),
        ) ?: return
        var completed = false
        try {
            processSingleOcrFallback(snapshot, parser)
            completed = true
        } finally {
            if (completed) {
                opaquePageDebouncer.commit(reservation, elapsedRealtimeMillis())
            } else {
                opaquePageDebouncer.release(reservation)
            }
        }
    }

    private suspend fun processSignedPage(
        parsed: ExtractionOutcome,
        snapshot: PageSnapshot,
        parser: PaymentPageParser,
    ): Boolean = when (parsed) {
        is ExtractionOutcome.Single -> candidateProcessor.process(parsed.candidate).isTerminal()
        is ExtractionOutcome.Multiple -> {
            parsed.options.forEach { candidateProcessor.process(it).isTerminal() }
            true
        }
        is ExtractionOutcome.Rejected -> if (
            parsed.reason == RecognitionRejectReason.INCOMPLETE_FIELDS
        ) {
            processSingleOcrFallback(snapshot, parser)
        } else {
            true
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
    ): Boolean = when (val captured = captureGateway.capture()) {
            is CaptureResult.Success -> processCapturedBitmap(captured, original, parser)
            is CaptureResult.Failure -> {
                diagnostics.record(captured.reason.toDiagnostic())
                false
            }
            CaptureResult.RequiresSystemConsent -> {
                diagnostics.record(RecognitionDiagnostic.PERMISSION_MISSING)
                false
            }
        }

    private suspend fun processCapturedBitmap(
        captured: CaptureResult.Success,
        original: PageSnapshot,
        parser: PaymentPageParser,
    ): Boolean {
        val bitmap = captured.bitmap
        try {
            if (captured.foregroundPackage != original.packageName) {
                diagnostics.record(RecognitionDiagnostic.CAPTURE_FAILED)
                return false
            }
            val ocrElements = try {
                textRecognizer.recognize(bitmap)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                diagnostics.record(RecognitionDiagnostic.OCR_FAILED)
                return false
            }
            val elements = TrustedAccessibilityAnchorMerger.merge(original, ocrElements)
            val ocrSnapshot = PageSnapshot(
                packageName = original.packageName,
                elements = elements,
                observedAt = original.observedAt,
            )
            return when (val parsed = parser.parse(ocrSnapshot)) {
                is ExtractionOutcome.Single -> candidateProcessor.process(parsed.candidate).isTerminal()
                is ExtractionOutcome.Multiple -> {
                    parsed.options.forEach { candidateProcessor.process(it).isTerminal() }
                    true
                }
                is ExtractionOutcome.Rejected ->
                    parsed.reason == RecognitionRejectReason.CONFLICTING_DIRECTION
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

    private fun RecognitionOutcome.isTerminal(): Boolean = when (this) {
        is RecognitionOutcome.Saved,
        is RecognitionOutcome.NeedsSelection,
        RecognitionOutcome.Duplicate,
        is RecognitionOutcome.Rejected,
        -> true
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
        private const val OPAQUE_PAGE_WINDOW_MILLIS = 2_000L
        private const val OPAQUE_PAGE_MAX_ENTRIES = 64
    }
}
