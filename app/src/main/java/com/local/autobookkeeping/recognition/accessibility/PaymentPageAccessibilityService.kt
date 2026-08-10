package com.local.autobookkeeping.recognition.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.local.autobookkeeping.AutoBookkeepingApplication
import com.local.autobookkeeping.recognition.RecognitionDiagnostic
import com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGateway
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.ManualCaptureProvider
import com.local.autobookkeeping.recognition.capture.ManualCaptureReservation
import com.local.autobookkeeping.recognition.capture.mapScreenshotError
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class PaymentPageAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packageGuard = ActiveWindowPackageGuard(ALLOWED_PACKAGES)
    private val manualWindowGuard = ManualCaptureWindowGuard(
        hostPackage = "com.local.autobookkeeping",
        transientPackages = setOf(SYSTEM_UI_PACKAGE),
    )
    private var captureGateway: AccessibilityCaptureGateway? = null
    private var controller: AutoPageRecognitionController? = null

    @Volatile
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val graph = (application as? AutoBookkeepingApplication)?.applicationGraph ?: return
        val gateway = graph.accessibilityCaptureGateway
        captureGateway = gateway
        controller = graph.automaticPageRecognitionController
        gateway.attach(::takeAccessibilityScreenshot)
        gateway.attachManual(
            object : ManualCaptureProvider {
                override fun reserve(): ManualCaptureReservation? =
                    reserveManualAccessibilityCapture()

                override suspend fun capture(
                    reservation: ManualCaptureReservation,
                ): CaptureResult = takeManualAccessibilityScreenshot(reservation)
            },
        )
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
        captureGateway?.detach()
        captureGateway = null
        controller = null
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

    private fun reserveManualAccessibilityCapture(): ManualCaptureWindowReservation? =
        manualWindowSnapshot()?.let(manualWindowGuard::reserve)

    private suspend fun takeManualAccessibilityScreenshot(
        untypedReservation: ManualCaptureReservation,
    ): CaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CaptureResult.RequiresSystemConsent
        }
        val reservation = untypedReservation as? ManualCaptureWindowReservation
            ?: return CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY)
        if (!manualWindowGuard.isCaptureWindowCurrent(reservation, manualWindowSnapshot())) {
            return CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY)
        }
        return AccessibilityScreenshotApi30.take(
            service = this,
            packageName = reservation.foregroundPackage,
            validateDelivery = { captured ->
                manualWindowGuard.validateDelivery(
                    captured = captured,
                    reservation = reservation,
                    current = manualWindowSnapshot(),
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

    private fun manualWindowSnapshot(): ManualWindowSnapshot? {
        val currentWindows = try {
            windows
        } catch (_: Exception) {
            return null
        }
        return try {
            val proofs = currentWindows.map { window ->
                window.toManualWindowProof() ?: return null
            }
            ManualWindowSnapshot(
                activePackage = proofs.singleOrNull(ManualWindowProof::active)?.packageName,
                windows = proofs,
            )
        } finally {
            currentWindows.forEach { window ->
                try {
                    @Suppress("DEPRECATION")
                    window.recycle()
                } catch (_: Exception) {
                    Unit
                }
            }
        }
    }

    private fun AccessibilityWindowInfo.toManualWindowProof(): ManualWindowProof? = try {
        val screenBounds = Rect()
        getBoundsInScreen(screenBounds)
        val root = try {
            root
        } catch (_: Exception) {
            null
        }
        var packageName: String? = null
        var rootReadable = root != null
        if (root != null) {
            try {
                packageName = root.packageName?.toString()
                if (packageName == null) rootReadable = false
            } catch (_: Exception) {
                rootReadable = false
            } finally {
                try {
                    AccessibilitySnapshotFactory.release(root)
                } catch (_: Exception) {
                    Unit
                }
            }
        }
        ManualWindowProof(
            id = id,
            packageName = packageName,
            type = type,
            displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) displayId else null,
            layer = layer,
            bounds = ManualWindowBounds(
                left = screenBounds.left,
                top = screenBounds.top,
                right = screenBounds.right,
                bottom = screenBounds.bottom,
            ),
            active = isActive,
            focused = isFocused,
            rootReadable = rootReadable,
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
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
        packageName: String?,
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
