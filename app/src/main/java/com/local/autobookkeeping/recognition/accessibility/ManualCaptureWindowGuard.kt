package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult

internal data class ManualCaptureWindowReservation(
    val foregroundPackage: String?,
    internal val activePackage: String,
    internal val visibleExternalPackages: Set<String>,
)

internal class ManualCaptureWindowGuard(
    private val hostPackage: String,
    private val transientPackages: Set<String>,
) {
    fun reserve(
        activePackage: String?,
        visiblePackages: Set<String>,
    ): ManualCaptureWindowReservation? {
        val active = activePackage
            ?.takeUnless { it in transientPackages }
            ?: return null
        val externalPackages = visiblePackages.externalPackages()
        val foregroundPackage = when {
            active != hostPackage -> active
            externalPackages.size == 1 -> externalPackages.single()
            else -> null
        }
        return ManualCaptureWindowReservation(
            foregroundPackage = foregroundPackage,
            activePackage = active,
            visibleExternalPackages = externalPackages,
        )
    }

    fun validateDelivery(
        captured: CaptureResult,
        reservation: ManualCaptureWindowReservation,
        activePackage: String?,
        visiblePackages: Set<String>,
    ): CaptureResult {
        if (captured !is CaptureResult.Success) return captured
        val stillCurrent = activePackage == reservation.activePackage &&
            visiblePackages.externalPackages() == reservation.visibleExternalPackages &&
            captured.foregroundPackage == reservation.foregroundPackage
        if (stillCurrent) return captured
        if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
        return CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY)
    }

    private fun Set<String>.externalPackages(): Set<String> =
        filterTo(linkedSetOf()) { packageName ->
            packageName != hostPackage && packageName !in transientPackages
        }
}
