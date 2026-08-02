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

class ManualCaptureSession internal constructor(
    internal val token: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is ManualCaptureSession && other.token == token

    override fun hashCode(): Int = token.hashCode()
}

class AccessibilityCaptureGateway internal constructor(
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val providerLock = Any()
    private val captureMutex = Mutex()
    private var provider: (suspend () -> CaptureResult)? = null
    private var manualProvider: (suspend () -> CaptureResult)? = null
    private var activeManualSession: SessionRecord? = null
    private var nextManualToken = 0L

    fun attach(provider: suspend () -> CaptureResult) {
        synchronized(providerLock) {
            this.provider = provider
        }
    }

    fun attachManual(provider: suspend () -> CaptureResult) {
        synchronized(providerLock) {
            manualProvider = provider
        }
    }

    fun beginManualCaptureSession(): ManualCaptureSession = synchronized(providerLock) {
        val session = ManualCaptureSession(++nextManualToken)
        activeManualSession = SessionRecord(
            session = session,
            expiresAtMillis = elapsedRealtimeMillis() + MANUAL_SESSION_TTL_MILLIS,
        )
        session
    }

    fun detach() {
        synchronized(providerLock) {
            provider = null
            manualProvider = null
            activeManualSession = null
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


    suspend fun capture(session: ManualCaptureSession): CaptureResult = captureMutex.withLock {
        val current = synchronized(providerLock) {
            val record = activeManualSession
            activeManualSession = null
            manualProvider.takeIf {
                record?.session == session && elapsedRealtimeMillis() <= record.expiresAtMillis
            }
        } ?: return@withLock CaptureResult.RequiresSystemConsent
        try {
            current()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
    }

    private data class SessionRecord(
        val session: ManualCaptureSession,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val MANUAL_SESSION_TTL_MILLIS = 2_000L
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
