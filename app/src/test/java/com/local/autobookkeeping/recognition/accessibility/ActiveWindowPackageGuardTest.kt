package com.local.autobookkeeping.recognition.accessibility

import android.graphics.Bitmap
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActiveWindowPackageGuardTest {
    private val guard = ActiveWindowPackageGuard(
        allowedPackages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone"),
    )

    @Test
    fun `cached allowed package cannot reserve when live window is other or missing`() {
        assertNull(guard.reserve("com.tencent.mm", "com.example.bank"))
        assertNull(guard.reserve("com.tencent.mm", null))
        assertNull(guard.reserve(null, "com.tencent.mm"))
    }

    @Test
    fun `same live allowed package can reserve capture`() {
        val reservation = guard.reserve("com.tencent.mm", "com.tencent.mm")

        assertNotNull(reservation)
    }

    @Test
    fun `capture delivery rejects missing other or changed live window`() {
        val reservation = requireNotNull(guard.reserve("com.tencent.mm", "com.tencent.mm"))

        assertFalse(guard.isStillCurrent(reservation, null))
        assertFalse(guard.isStillCurrent(reservation, "com.example.bank"))
        assertFalse(guard.isStillCurrent(reservation, "com.eg.android.AlipayGphone"))
        assertTrue(guard.isStillCurrent(reservation, "com.tencent.mm"))
    }

    @Test
    fun `invalid live window recycles software bitmap instead of delivering capture`() {
        val reservation = requireNotNull(guard.reserve("com.tencent.mm", "com.tencent.mm"))
        listOf(null, "com.example.bank", "com.eg.android.AlipayGphone").forEach { livePackage ->
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

            val result = guard.validateDelivery(
                CaptureResult.Success(bitmap, "com.tencent.mm"),
                reservation,
                livePackage,
            )

            assertEquals(CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY), result)
            assertTrue(bitmap.isRecycled)
        }
    }

    @Test
    fun `same live window delivers software bitmap without recycling it`() {
        val reservation = requireNotNull(guard.reserve("com.tencent.mm", "com.tencent.mm"))
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val captured = CaptureResult.Success(bitmap, "com.tencent.mm")

        val result = guard.validateDelivery(captured, reservation, "com.tencent.mm")

        assertSame(captured, result)
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun `capture tagged with changed package is rejected even when live window returned`() {
        val reservation = requireNotNull(guard.reserve("com.tencent.mm", "com.tencent.mm"))
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        val result = guard.validateDelivery(
            CaptureResult.Success(bitmap, "com.eg.android.AlipayGphone"),
            reservation,
            "com.tencent.mm",
        )

        assertEquals(CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY), result)
        assertTrue(bitmap.isRecycled)
    }
}
