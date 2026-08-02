package com.local.autobookkeeping.recognition.accessibility

import android.graphics.Bitmap
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManualCaptureWindowGuardTest {
    private val guard = ManualCaptureWindowGuard(
        hostPackage = HOST_PACKAGE,
        transientPackages = setOf(SYSTEM_UI_PACKAGE),
    )

    @Test
    fun `transparent host attributes only one proven external window`() {
        val wechat = guard.reserve(HOST_PACKAGE, setOf(HOST_PACKAGE, WECHAT_PACKAGE))
        val bank = guard.reserve(HOST_PACKAGE, setOf(HOST_PACKAGE, "com.example.bank"))

        assertEquals(WECHAT_PACKAGE, wechat?.foregroundPackage)
        assertEquals("com.example.bank", bank?.foregroundPackage)
    }

    @Test
    fun `ambiguous or missing external window keeps attribution null`() {
        val missing = guard.reserve(HOST_PACKAGE, setOf(HOST_PACKAGE))
        val ambiguous = guard.reserve(
            HOST_PACKAGE,
            setOf(HOST_PACKAGE, WECHAT_PACKAGE, "com.example.bank"),
        )

        assertNull(missing?.foregroundPackage)
        assertNull(ambiguous?.foregroundPackage)
    }

    @Test
    fun `quick settings panel cannot be captured as the bill page`() {
        assertNull(
            guard.reserve(
                SYSTEM_UI_PACKAGE,
                setOf(SYSTEM_UI_PACKAGE, WECHAT_PACKAGE),
            ),
        )
    }

    @Test
    fun `delivery rejects changed window proof and recycles bitmap`() {
        val reservation = requireNotNull(
            guard.reserve(HOST_PACKAGE, setOf(HOST_PACKAGE, WECHAT_PACKAGE)),
        )
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        val result = guard.validateDelivery(
            captured = CaptureResult.Success(bitmap, WECHAT_PACKAGE),
            reservation = reservation,
            activePackage = HOST_PACKAGE,
            visiblePackages = setOf(HOST_PACKAGE, "com.example.bank"),
        )

        assertEquals(CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY), result)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `delivery accepts the same proof without recycling software bitmap`() {
        val visible = setOf(HOST_PACKAGE, WECHAT_PACKAGE)
        val reservation = requireNotNull(guard.reserve(HOST_PACKAGE, visible))
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val captured = CaptureResult.Success(bitmap, WECHAT_PACKAGE)

        val result = guard.validateDelivery(
            captured = captured,
            reservation = reservation,
            activePackage = HOST_PACKAGE,
            visiblePackages = visible,
        )

        assertEquals(captured, result)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    private companion object {
        const val HOST_PACKAGE = "com.local.autobookkeeping"
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
