package com.local.autobookkeeping.recognition.capture

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityCaptureGatewayGenerationTest {
    @Test
    fun `automatic success returned after detach is recycled and not delivered`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bitmap = bitmap()
        gateway.attach {
            entered.complete(Unit)
            release.await()
            CaptureResult.Success(bitmap, "com.tencent.mm")
        }

        val capture = async { gateway.capture() }
        entered.await()
        gateway.detach()
        release.complete(Unit)

        assertEquals(CaptureResult.RequiresSystemConsent, capture.await())
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `reattached automatic provider is isolated from old in flight success`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleBitmap = bitmap()
        gateway.attach {
            entered.complete(Unit)
            release.await()
            CaptureResult.Success(staleBitmap, "com.tencent.mm")
        }
        val staleCapture = async { gateway.capture() }
        entered.await()
        gateway.attach { CaptureResult.Failure(CaptureFailure.SECURE_WINDOW) }
        release.complete(Unit)

        assertEquals(CaptureResult.RequiresSystemConsent, staleCapture.await())
        assertTrue(staleBitmap.isRecycled)
        assertEquals(CaptureResult.Failure(CaptureFailure.SECURE_WINDOW), gateway.capture())
    }

    @Test
    fun `manual success returned after detach is recycled and not delivered`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bitmap = bitmap()
        gateway.attachManual {
            entered.complete(Unit)
            release.await()
            CaptureResult.Success(bitmap, "com.example.bank")
        }
        val session = gateway.beginManualCaptureSession()

        val capture = async { gateway.capture(session) }
        entered.await()
        gateway.detach()
        release.complete(Unit)

        assertEquals(
            CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY),
            capture.await(),
        )
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `reattached manual provider is isolated from old in flight success`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleBitmap = bitmap()
        gateway.attachManual {
            entered.complete(Unit)
            release.await()
            CaptureResult.Success(staleBitmap, "com.example.bank")
        }
        val staleSession = gateway.beginManualCaptureSession()
        val staleCapture = async { gateway.capture(staleSession) }
        entered.await()
        gateway.attachManual { CaptureResult.Failure(CaptureFailure.SECURE_WINDOW) }
        release.complete(Unit)

        assertEquals(
            CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY),
            staleCapture.await(),
        )
        assertTrue(staleBitmap.isRecycled)
        val currentSession = gateway.beginManualCaptureSession()
        assertEquals(
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW),
            gateway.capture(currentSession),
        )
    }

    private companion object {
        fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    }
}
