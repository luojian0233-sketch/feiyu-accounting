package com.local.autobookkeeping.recognition.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.local.autobookkeeping.AutoBookkeepingApplication
import com.local.autobookkeeping.recognition.RecognitionDiagnostic
import com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGateway
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.MlKitTextRecognizer
import com.local.autobookkeeping.recognition.capture.mapScreenshotError
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class PaymentPageAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureGateway = AccessibilityCaptureGateway()
    private val packageGuard = ActiveWindowPackageGuard(ALLOWED_PACKAGES)
    private var controller: AutoPageRecognitionController? = null
    private var textRecognizer: MlKitTextRecognizer? = null

    @Volatile
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val graph = (application as? AutoBookkeepingApplication)?.applicationGraph ?: return
        textRecognizer?.close()
        val recognizer = MlKitTextRecognizer()
        textRecognizer = recognizer
        controller = AutoPageRecognitionController(
            settings = AutomaticPageRecognitionSettings { packageName ->
                when (packageName) {
                    AutoPageRecognitionController.WECHAT_PACKAGE ->
                        graph.settingsRepository.wechatEnabled.first()
                    AutoPageRecognitionController.ALIPAY_PACKAGE ->
                        graph.settingsRepository.alipayEnabled.first()
                    else -> false
                }
            },
            captureGateway = captureGateway,
            textRecognizer = recognizer,
            coordinator = graph.recognitionCoordinator,
            diagnostics = graph.recognitionDiagnostics,
        )
        captureGateway.attach(::takeAccessibilityScreenshot)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in RECOGNIZED_EVENT_TYPES) return
        val packageName = event.packageName?.toString()
        if (packageName !in ALLOWED_PACKAGES) {
            foregroundPackage = null
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != packageName) {
            foregroundPackage = null
            AccessibilitySnapshotFactory.release(root)
            return
        }
        foregroundPackage = packageName
        val snapshot = AccessibilitySnapshotFactory.create(
            root = root,
            packageName = packageName,
            observedAt = Instant.now(),
        )
        val activeController = controller ?: return
        serviceScope.launch {
            try {
                activeController.onWindowChanged(snapshot)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val graph = (application as? AutoBookkeepingApplication)?.applicationGraph
                graph?.recognitionDiagnostics?.record(RecognitionDiagnostic.CAPTURE_FAILED)
            }
        }
    }

    override fun onInterrupt() {
        foregroundPackage = null
    }

    override fun onDestroy() {
        foregroundPackage = null
        captureGateway.detach()
        controller = null
        textRecognizer?.close()
        textRecognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun takeAccessibilityScreenshot(): CaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureResult.RequiresSystemConsent
        }
        val reservation = packageGuard.reserve(
            cachedPackage = foregroundPackage,
            livePackage = activeWindowPackage(),
        ) ?: return CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY)
        return AccessibilityScreenshotApi30.take(
            service = this,
            packageName = reservation.packageName,
            validateDelivery = { captured ->
                packageGuard.validateDelivery(
                    captured = captured,
                    reservation = reservation,
                    livePackage = activeWindowPackage(),
                )
            },
        )
    }

    private fun activeWindowPackage(): String? {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null
        var packageName: String? = null
        try {
            packageName = root.packageName?.toString()
        } catch (_: Exception) {
            Unit
        } finally {
            try {
                AccessibilitySnapshotFactory.release(root)
            } catch (_: Exception) {
                Unit
            }
        }
        return packageName
    }

    private companion object {
        val ALLOWED_PACKAGES = setOf(
            AutoPageRecognitionController.WECHAT_PACKAGE,
            AutoPageRecognitionController.ALIPAY_PACKAGE,
        )
        val RECOGNIZED_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.R)
private object AccessibilityScreenshotApi30 {
    suspend fun take(
        service: AccessibilityService,
        packageName: String,
        validateDelivery: (CaptureResult) -> CaptureResult,
    ): CaptureResult = suspendCancellableCoroutine { continuation ->
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val captured = screenshot.toSoftwareCapture(packageName)
                        continuation.resumeCapture(
                            validateDelivery(captured),
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resumeCapture(
                            CaptureResult.Failure(mapScreenshotError(errorCode)),
                        )
                    }
                },
            )
        } catch (_: SecurityException) {
            continuation.resumeCapture(
                CaptureResult.Failure(CaptureFailure.NO_ACCESSIBILITY_ACCESS),
            )
        } catch (_: Exception) {
            continuation.resumeCapture(CaptureResult.Failure(CaptureFailure.INTERNAL))
        }
    }

    private fun CancellableContinuation<CaptureResult>.resumeCapture(result: CaptureResult) {
        resume(result) { _, cancelledResult, _ ->
            if (cancelledResult is CaptureResult.Success && !cancelledResult.bitmap.isRecycled) {
                cancelledResult.bitmap.recycle()
            }
        }
    }

    private fun AccessibilityService.ScreenshotResult.toSoftwareCapture(
        packageName: String?,
    ): CaptureResult {
        val buffer = hardwareBuffer
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?: return CaptureResult.Failure(CaptureFailure.INTERNAL)
            try {
                val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: return CaptureResult.Failure(CaptureFailure.INTERNAL)
                CaptureResult.Success(softwareBitmap, packageName)
            } finally {
                if (!hardwareBitmap.isRecycled) hardwareBitmap.recycle()
            }
        } catch (_: Exception) {
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        } finally {
            buffer.close()
        }
    }
}
