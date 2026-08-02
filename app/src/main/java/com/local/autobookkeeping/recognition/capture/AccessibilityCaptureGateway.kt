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

internal interface ManualCaptureReservation

internal interface ManualCaptureProvider {
    fun reserve(): ManualCaptureReservation?
    suspend fun capture(reservation: ManualCaptureReservation): CaptureResult
}

class AccessibilityCaptureGateway internal constructor(
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val providerLock = Any()
    private val captureMutex = Mutex()
    private var provider: (suspend () -> CaptureResult)? = null
    private var manualProvider: ManualCaptureProvider? = null
    private var providerGeneration = 0L
    private var manualProviderGeneration = 0L
    private var activeManualSession: SessionRecord? = null
    private var nextManualToken = 0L

    fun attach(provider: suspend () -> CaptureResult) {
        synchronized(providerLock) {
            providerGeneration += 1
            this.provider = provider
        }
    }

    fun attachManual(provider: suspend () -> CaptureResult) {
        attachManual(LambdaManualCaptureProvider(provider))
    }

    internal fun attachManual(provider: ManualCaptureProvider) {
        synchronized(providerLock) {
            manualProviderGeneration += 1
            manualProvider = provider
        }
    }

    fun beginManualCaptureSession(): ManualCaptureSession {
        val (session, binding) = synchronized(providerLock) {
            val created = ManualCaptureSession(++nextManualToken)
            activeManualSession = null
            created to manualProvider?.let {
                ManualProviderBinding(it, manualProviderGeneration)
            }
        }
        val reservation = try {
            binding?.provider?.reserve()
        } catch (_: Exception) {
            null
        }
        synchronized(providerLock) {
            if (
                session.token == nextManualToken &&
                binding != null &&
                reservation != null &&
                isCurrent(binding)
            ) {
                activeManualSession = SessionRecord(
                    session = session,
                    expiresAtMillis = elapsedRealtimeMillis() + MANUAL_SESSION_TTL_MILLIS,
                    binding = binding,
                    reservation = reservation,
                )
            }
        }
        return session
    }

    fun detach() {
        synchronized(providerLock) {
            providerGeneration += 1
            manualProviderGeneration += 1
            provider = null
            manualProvider = null
            activeManualSession = null
        }
    }

    suspend fun capture(): CaptureResult = captureMutex.withLock {
        val binding = synchronized(providerLock) {
            provider?.let { ProviderBinding(it, providerGeneration) }
        }
            ?: return@withLock CaptureResult.RequiresSystemConsent
        val result = try {
            binding.provider()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
        validateBinding(binding, result)
    }


    suspend fun capture(session: ManualCaptureSession): CaptureResult = captureMutex.withLock {
        val invocation = synchronized(providerLock) {
            val record = activeManualSession
            if (record?.session != session) {
                null
            } else {
                activeManualSession = null
                record.binding.takeIf {
                    elapsedRealtimeMillis() <= record.expiresAtMillis &&
                        it != null && isCurrent(it)
                }?.let { ManualInvocation(it, record.reservation) }
            }
        } ?: return@withLock CaptureResult.RequiresSystemConsent
        val result = try {
            invocation.binding.provider.capture(invocation.reservation)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
        validateBinding(invocation.binding, result)
    }

    private fun validateBinding(binding: ProviderBinding, result: CaptureResult): CaptureResult {
        val isCurrent = synchronized(providerLock) {
            providerGeneration == binding.generation && provider === binding.provider
        }
        return result.takeIf { isCurrent } ?: result.invalidated()
    }

    private fun validateBinding(
        binding: ManualProviderBinding,
        result: CaptureResult,
    ): CaptureResult {
        val isCurrent = synchronized(providerLock) { isCurrent(binding) }
        return result.takeIf { isCurrent } ?: result.invalidated()
    }

    private fun isCurrent(binding: ManualProviderBinding): Boolean =
        manualProviderGeneration == binding.generation && manualProvider === binding.provider

    private fun CaptureResult.invalidated(): CaptureResult {
        if (this is CaptureResult.Success && !bitmap.isRecycled) bitmap.recycle()
        return CaptureResult.RequiresSystemConsent
    }

    private data class SessionRecord(
        val session: ManualCaptureSession,
        val expiresAtMillis: Long,
        val binding: ManualProviderBinding?,
        val reservation: ManualCaptureReservation,
    )

    private data class ProviderBinding(
        val provider: suspend () -> CaptureResult,
        val generation: Long,
    )

    private data class ManualProviderBinding(
        val provider: ManualCaptureProvider,
        val generation: Long,
    )

    private data class ManualInvocation(
        val binding: ManualProviderBinding,
        val reservation: ManualCaptureReservation,
    )

    private class LambdaManualCaptureProvider(
        private val capture: suspend () -> CaptureResult,
    ) : ManualCaptureProvider {
        override fun reserve(): ManualCaptureReservation = LambdaReservation

        override suspend fun capture(reservation: ManualCaptureReservation): CaptureResult =
            capture()
    }

    private data object LambdaReservation : ManualCaptureReservation

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
