package com.local.autobookkeeping.recognition.tile

import android.graphics.Bitmap
import android.app.Activity
import android.content.Intent
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.RecognitionRejectReason
import com.local.autobookkeeping.recognition.capture.CaptureFailure
import com.local.autobookkeeping.recognition.capture.CaptureResult
import com.local.autobookkeeping.recognition.capture.ManualCaptureSession
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenshotCaptureSessionRunnerTest {
    @Test
    fun `repeated resume waits 350 milliseconds captures once and finishes once`() = runTest {
        val bitmap = bitmap()
        val delays = mutableListOf<Long>()
        var captureCalls = 0
        var processCalls = 0
        var finishCalls = 0
        val expected = RecognitionOutcome.Rejected(RecognitionRejectReason.NOT_PAYMENT_PAGE)
        val runner = ScreenshotCaptureSessionRunner(
            capture = {
                captureCalls += 1
                CaptureResult.Success(bitmap, "com.example.bank")
            },
            process = { captured, packageName, observedAt ->
                processCalls += 1
                assertSame(bitmap, captured)
                assertEquals("com.example.bank", packageName)
                assertEquals(NOW, observedAt)
                expected
            },
            delay = { delays += it },
            now = { NOW },
            finish = { finishCalls += 1 },
        )
        val session = ManualCaptureSession(7L)

        val first = runner.runOnce(session)
        val second = runner.runOnce(session)

        assertEquals(expected, first)
        assertEquals(null, second)
        assertEquals(listOf(350L), delays)
        assertEquals(1, captureCalls)
        assertEquals(1, processCalls)
        assertEquals(1, finishCalls)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `capture failures and consent requirement map to safe rejected outcomes and finish`() = runTest {
        val cases = listOf(
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW) to RecognitionRejectReason.SECURE_WINDOW,
            CaptureResult.Failure(CaptureFailure.TOO_FREQUENT) to RecognitionRejectReason.CAPTURE_FAILED,
            CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY) to RecognitionRejectReason.CAPTURE_FAILED,
            CaptureResult.Failure(CaptureFailure.INTERNAL) to RecognitionRejectReason.CAPTURE_FAILED,
        )

        cases.forEachIndexed { index, (captured, expectedReason) ->
            var finishCalls = 0
            var processCalls = 0
            val runner = ScreenshotCaptureSessionRunner(
                capture = { captured },
                process = { _, _, _ ->
                    processCalls += 1
                    error("failure paths must not process a bitmap")
                },
                delay = {},
                now = { NOW },
                finish = { finishCalls += 1 },
            )

            val result = runner.runOnce(ManualCaptureSession(index.toLong() + 1))

            assertEquals(RecognitionOutcome.Rejected(expectedReason), result)
            assertEquals(0, processCalls)
            assertEquals(1, finishCalls)
        }
    }

    @Test
    fun `only unavailable accessibility channel requests system consent without finishing`() = runTest {
        val fallbackCases = listOf(
            CaptureResult.RequiresSystemConsent,
            CaptureResult.Failure(CaptureFailure.NO_ACCESSIBILITY_ACCESS),
        )

        fallbackCases.forEachIndexed { index, captured ->
            var consentRequests = 0
            var finishCalls = 0
            var processCalls = 0
            val runner = ScreenshotCaptureSessionRunner(
                capture = { captured },
                process = { _, _, _ ->
                    processCalls += 1
                    error("fallback must not process before consent")
                },
                delay = {},
                now = { NOW },
                requestSystemConsent = { consentRequests += 1 },
                finish = { finishCalls += 1 },
            )

            val result = runner.runOnce(ManualCaptureSession(index.toLong() + 100))

            assertEquals(null, result)
            assertEquals(1, consentRequests)
            assertEquals(0, processCalls)
            assertEquals(0, finishCalls)
        }
    }

    @Test
    fun `secure and window identity failures never bypass accessibility safety checks`() = runTest {
        val failClosedCases = listOf(
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW),
            CaptureResult.Failure(CaptureFailure.INVALID_DISPLAY),
            CaptureResult.Failure(CaptureFailure.TOO_FREQUENT),
            CaptureResult.Failure(CaptureFailure.INTERNAL),
        )

        failClosedCases.forEachIndexed { index, captured ->
            var consentRequests = 0
            var finishCalls = 0
            val runner = ScreenshotCaptureSessionRunner(
                capture = { captured },
                process = { _, _, _ -> error("failure must not process") },
                delay = {},
                requestSystemConsent = { consentRequests += 1 },
                finish = { finishCalls += 1 },
            )

            runner.runOnce(ManualCaptureSession(index.toLong() + 200))

            assertEquals(0, consentRequests)
            assertEquals(1, finishCalls)
        }
    }

    @Test
    fun `denied or cancelled projection consent finishes with redacted diagnostic and no work`() {
        val cases = listOf(
            Activity.RESULT_CANCELED to null,
            Activity.RESULT_CANCELED to Intent("private-consent-data"),
            Activity.RESULT_OK to null,
        )

        cases.forEach { (resultCode, data) ->
            val diagnostics = mutableListOf<RecognitionRejectReason>()
            var starts = 0
            var finishCalls = 0
            val handler = ProjectionConsentResultHandler(
                startProjection = { _, _ -> starts += 1 },
                report = diagnostics::add,
                finish = { finishCalls += 1 },
            )

            handler.handle(resultCode, data)

            assertEquals(0, starts)
            assertEquals(listOf(RecognitionRejectReason.PERMISSION_MISSING), diagnostics)
            assertEquals(1, finishCalls)
        }
    }

    @Test
    fun `granted projection consent starts once then finishes without reporting raw data`() {
        val token = Intent("private-consent-data")
        var receivedCode: Int? = null
        var receivedToken: Intent? = null
        val diagnostics = mutableListOf<RecognitionRejectReason>()
        var finishCalls = 0
        val handler = ProjectionConsentResultHandler(
            startProjection = { resultCode, data ->
                receivedCode = resultCode
                receivedToken = data
            },
            report = diagnostics::add,
            finish = { finishCalls += 1 },
        )

        handler.handle(Activity.RESULT_OK, token)
        handler.handle(Activity.RESULT_OK, token)

        assertEquals(Activity.RESULT_OK, receivedCode)
        assertSame(token, receivedToken)
        assertTrue(diagnostics.isEmpty())
        assertEquals(1, finishCalls)
    }

    @Test
    fun `processor failure is redacted after recycling bitmap and finishing activity`() = runTest {
        val bitmap = bitmap()
        val failure = IllegalStateException("redacted processing failure")
        var finishCalls = 0
        val runner = ScreenshotCaptureSessionRunner(
            capture = { CaptureResult.Success(bitmap, null) },
            process = { _, _, _ -> throw failure },
            delay = {},
            now = { NOW },
            finish = { finishCalls += 1 },
        )

        val result = runner.runOnce(ManualCaptureSession(9L))

        assertEquals(RecognitionOutcome.Rejected(RecognitionRejectReason.CAPTURE_FAILED), result)
        assertTrue(bitmap.isRecycled)
        assertEquals(1, finishCalls)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-03T12:00:00Z")
        fun bitmap(): Bitmap = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
    }
}
