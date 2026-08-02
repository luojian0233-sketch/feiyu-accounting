package com.local.autobookkeeping.recognition.accessibility

import android.view.accessibility.AccessibilityWindowInfo
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.ManualCaptureReservation

internal data class ManualWindowBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left)
        require(bottom >= top)
    }
}

internal data class ManualWindowProof(
    val id: Int,
    val packageName: String?,
    val type: Int,
    val displayId: Int?,
    val layer: Int,
    val bounds: ManualWindowBounds,
    val active: Boolean,
    val focused: Boolean,
    val rootReadable: Boolean,
)

internal data class ManualWindowSnapshot(
    val activePackage: String?,
    val windows: List<ManualWindowProof>,
)

internal data class ManualCaptureWindowReservation(
    val foregroundPackage: String,
    val externalWindow: ManualWindowProof,
) : ManualCaptureReservation

internal class ManualCaptureWindowGuard(
    private val hostPackage: String,
    private val transientPackages: Set<String>,
) {
    fun reserve(snapshot: ManualWindowSnapshot): ManualCaptureWindowReservation? {
        if (snapshot.activePackage == null) return null
        val external = snapshot.uniqueExternalApplicationWindow() ?: return null
        return ManualCaptureWindowReservation(
            foregroundPackage = requireNotNull(external.packageName),
            externalWindow = external,
        )
    }

    fun isCaptureWindowCurrent(
        reservation: ManualCaptureWindowReservation,
        current: ManualWindowSnapshot?,
    ): Boolean = current?.activePackage == hostPackage &&
        !current.hasTransientObstruction() &&
        current.uniqueExternalApplicationWindow() == reservation.externalWindow

    fun validateDelivery(
        captured: CaptureResult,
        reservation: ManualCaptureWindowReservation,
        current: ManualWindowSnapshot?,
    ): CaptureResult {
        if (captured !is CaptureResult.Success) return captured
        if (
            captured.foregroundPackage == reservation.foregroundPackage &&
            isCaptureWindowCurrent(reservation, current)
        ) return captured
        if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
        return CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY)
    }

    private fun ManualWindowSnapshot.uniqueExternalApplicationWindow(): ManualWindowProof? {
        val applicationWindows = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }
        if (applicationWindows.any { !it.rootReadable || it.packageName == null }) return null
        return applicationWindows
            .filter { window ->
                window.packageName != hostPackage &&
                    window.packageName !in transientPackages
            }
            .singleOrNull()
    }

    private fun ManualWindowSnapshot.hasTransientObstruction(): Boolean = windows.any { window ->
        window.packageName in transientPackages && (window.active || window.focused)
    }
}
