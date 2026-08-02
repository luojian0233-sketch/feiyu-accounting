package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult

internal data class ActiveWindowPackageReservation(
    val packageName: String,
)

internal class ActiveWindowPackageGuard(
    private val allowedPackages: Set<String>,
) {
    fun reserve(
        cachedPackage: String?,
        livePackage: String?,
    ): ActiveWindowPackageReservation? = cachedPackage
        ?.takeIf { it in allowedPackages && it == livePackage }
        ?.let(::ActiveWindowPackageReservation)

    fun isStillCurrent(
        reservation: ActiveWindowPackageReservation,
        livePackage: String?,
    ): Boolean = livePackage in allowedPackages && livePackage == reservation.packageName

    fun validateDelivery(
        captured: CaptureResult,
        reservation: ActiveWindowPackageReservation,
        livePackage: String?,
    ): CaptureResult {
        if (captured !is CaptureResult.Success) {
            return captured
        }
        if (
            captured.foregroundPackage == reservation.packageName &&
            isStillCurrent(reservation, livePackage)
        ) return captured
        if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
        return CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY)
    }
}
