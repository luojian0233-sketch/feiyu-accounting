package com.local.autobookkeeping.recognition.tile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.local.autobookkeeping.AutoBookkeepingApplication
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.recognition.RecognitionDiagnostic
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
    private val requestSystemConsent: () -> Unit = {},
    private val finish: () -> Unit,
) {
    private val started = AtomicBoolean(false)

    suspend fun runOnce(session: ManualCaptureSession): RecognitionOutcome? {
        if (!started.compareAndSet(false, true)) return null
        var capturedBitmap: Bitmap? = null
        var finishAfterRun = true
        return try {
            try {
                delay(PANEL_COLLAPSE_DELAY_MILLIS)
                when (val captured = capture(session)) {
                    is CaptureResult.Success -> {
                        capturedBitmap = captured.bitmap
                        process(captured.bitmap, captured.foregroundPackage, now())
                    }
                    is CaptureResult.Failure -> if (captured.reason.allowsProjectionFallback()) {
                        requestSystemConsent()
                        finishAfterRun = false
                        null
                    } else {
                        RecognitionOutcome.Rejected(captured.reason.toRejectReason())
                    }
                    CaptureResult.RequiresSystemConsent -> {
                        requestSystemConsent()
                        finishAfterRun = false
                        null
                    }
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
            if (finishAfterRun) finish()
        }
    }

    private fun CaptureFailure.allowsProjectionFallback(): Boolean =
        this == CaptureFailure.NO_ACCESSIBILITY_ACCESS

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

internal class ProjectionConsentResultHandler(
    private val startProjection: (Int, Intent) -> Unit,
    private val report: (RecognitionRejectReason) -> Unit,
    private val finish: () -> Unit,
) {
    private val handled = AtomicBoolean(false)

    fun handle(resultCode: Int, data: Intent?) {
        if (!handled.compareAndSet(false, true)) return
        try {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startProjection(resultCode, data)
            } else {
                report(RecognitionRejectReason.PERMISSION_MISSING)
            }
        } catch (_: Exception) {
            report(RecognitionRejectReason.CAPTURE_FAILED)
        } finally {
            finish()
        }
    }
}

class ScreenshotCaptureActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: ScreenshotCaptureSessionRunner? = null
    private var session: ManualCaptureSession? = null
    private var consentHandler: ProjectionConsentResultHandler? = null

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
        consentHandler = ProjectionConsentResultHandler(
            startProjection = { resultCode, data ->
                startForegroundService(
                    ProjectionCaptureService.createIntent(this, resultCode, data),
                )
            },
            report = { reason ->
                graph.recognitionDiagnostics.record(reason.toDiagnostic())
            },
            finish = ::finishSafely,
        )
        runner = ScreenshotCaptureSessionRunner(
            capture = graph.accessibilityCaptureGateway::capture,
            process = graph.screenshotRecognitionController::process,
            requestSystemConsent = ::requestSystemConsent,
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

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            consentHandler?.handle(resultCode, data)
        }
    }

    private fun requestSystemConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun RecognitionRejectReason.toDiagnostic(): RecognitionDiagnostic = when (this) {
        RecognitionRejectReason.PERMISSION_MISSING -> RecognitionDiagnostic.PERMISSION_MISSING
        else -> RecognitionDiagnostic.CAPTURE_FAILED
    }

    private fun finishSafely() {
        if (!isFinishing && !isDestroyed) finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST_MEDIA_PROJECTION = 42010
    }
}
