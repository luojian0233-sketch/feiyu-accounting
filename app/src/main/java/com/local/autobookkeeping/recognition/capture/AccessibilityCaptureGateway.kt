package com.local.autobookkeeping.recognition.capture

import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CaptureFailure {
    SECURE_WINDOW,
    TOO_FREQUENT,
    NO_ACCESSIBILITY_ACCESS,
    INVALID_DISPLAY,
    INTERNAL,
}

sealed interface CaptureResult {
    data class Success(
        val bitmap: Bitmap,
        val foregroundPackage: String?,
    ) : CaptureResult

    data class Failure(val reason: CaptureFailure) : CaptureResult
    data object RequiresSystemConsent : CaptureResult
}

class AccessibilityCaptureGateway {
    private val providerLock = Any()
    private val captureMutex = Mutex()
    private var provider: (suspend () -> CaptureResult)? = null

    fun attach(provider: suspend () -> CaptureResult) {
        synchronized(providerLock) {
            this.provider = provider
        }
    }

    fun detach() {
        synchronized(providerLock) {
            provider = null
        }
    }

    suspend fun capture(): CaptureResult = captureMutex.withLock {
        val current = synchronized(providerLock) { provider }
            ?: return@withLock CaptureResult.RequiresSystemConsent
        try {
            current()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
    }
}

internal fun mapScreenshotError(errorCode: Int): CaptureFailure = when (errorCode) {
    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> CaptureFailure.SECURE_WINDOW
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> CaptureFailure.TOO_FREQUENT
    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
        CaptureFailure.NO_ACCESSIBILITY_ACCESS
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> CaptureFailure.INVALID_DISPLAY
    else -> CaptureFailure.INTERNAL
}
