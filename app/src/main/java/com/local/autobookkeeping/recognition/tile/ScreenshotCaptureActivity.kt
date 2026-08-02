package com.local.autobookkeeping.recognition.tile

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import com.local.autobookkeeping.AutoBookkeepingApplication
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.ManualCaptureSession
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ScreenshotCaptureSessionRunner(
    private val capture: suspend (ManualCaptureSession) -> CaptureResult,
    private val process: suspend (Bitmap, String?, Instant) -> RecognitionOutcome,
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val now: () -> Instant = Instant::now,
    private val finish: () -> Unit,
) {
    private val started = AtomicBoolean(false)

    suspend fun runOnce(session: ManualCaptureSession): RecognitionOutcome? {
        if (!started.compareAndSet(false, true)) return null
        var capturedBitmap: Bitmap? = null
        return try {
            try {
                delay(PANEL_COLLAPSE_DELAY_MILLIS)
                when (val captured = capture(session)) {
                    is CaptureResult.Success -> {
                        capturedBitmap = captured.bitmap
                        process(captured.bitmap, captured.foregroundPackage, now())
                    }
                    is CaptureResult.Failure ->
                        RecognitionOutcome.Rejected(captured.reason.toRejectReason())
                    CaptureResult.RequiresSystemConsent ->
                        RecognitionOutcome.Rejected(RecognitionRejectReason.PERMISSION_MISSING)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                RecognitionOutcome.Rejected(RecognitionRejectReason.CAPTURE_FAILED)
            }
        } finally {
            capturedBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            finish()
        }
    }

    private fun CaptureFailure.toRejectReason(): RecognitionRejectReason = when (this) {
        CaptureFailure.SECURE_WINDOW -> RecognitionRejectReason.SECURE_WINDOW
        CaptureFailure.NO_ACCESSIBILITY_ACCESS -> RecognitionRejectReason.PERMISSION_MISSING
        CaptureFailure.TOO_FREQUENT,
        CaptureFailure.INVALID_DISPLAY,
        CaptureFailure.INTERNAL,
        -> RecognitionRejectReason.CAPTURE_FAILED
    }

    private companion object {
        const val PANEL_COLLAPSE_DELAY_MILLIS = 350L
    }
}

class ScreenshotCaptureActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: ScreenshotCaptureSessionRunner? = null
    private var session: ManualCaptureSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        val token = intent?.getLongExtra(EXTRA_MANUAL_CAPTURE_SESSION, -1L) ?: -1L
        if (token <= 0L) {
            finishSafely()
            return
        }
        val graph = (application as? AutoBookkeepingApplication)?.applicationGraph
        if (graph == null) {
            finishSafely()
            return
        }
        session = ManualCaptureSession(token)
        runner = ScreenshotCaptureSessionRunner(
            capture = graph.accessibilityCaptureGateway::capture,
            process = graph.screenshotRecognitionController::process,
            finish = ::finishSafely,
        )
    }

    override fun onResume() {
        super.onResume()
        val activeRunner = runner ?: return
        val activeSession = session ?: return
        activityScope.launch {
            activeRunner.runOnce(activeSession)
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun finishSafely() {
        if (!isFinishing && !isDestroyed) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
