package com.local.autobookkeeping.recognition.capture

import android.accessibilityservice.AccessibilityService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccessibilityCaptureGatewayTest {
    @Test
    fun `manual session reserves proof before capture and binds it to the token`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        val reservation = object : ManualCaptureReservation {}
        var reserveCalls = 0
        var capturedReservation: ManualCaptureReservation? = null
        gateway.attachManual(
            object : ManualCaptureProvider {
                override fun reserve(): ManualCaptureReservation {
                    reserveCalls += 1
                    return reservation
                }

                override suspend fun capture(
                    reservation: ManualCaptureReservation,
                ): CaptureResult {
                    capturedReservation = reservation
                    return CaptureResult.Failure(CaptureFailure.SECURE_WINDOW)
                }
            },
        )

        val session = gateway.beginManualCaptureSession()

        assertEquals(1, reserveCalls)
        assertEquals(null, capturedReservation)
        assertEquals(CaptureResult.Failure(CaptureFailure.SECURE_WINDOW), gateway.capture(session))
        assertTrue(capturedReservation === reservation)
    }

    @Test
    fun `manual capture session invokes manual provider once and cannot be replayed`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var calls = 0
        gateway.attachManual {
            calls += 1
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW)
        }
        val session = gateway.beginManualCaptureSession()

        assertEquals(CaptureResult.Failure(CaptureFailure.SECURE_WINDOW), gateway.capture(session))
        assertEquals(CaptureResult.RequiresSystemConsent, gateway.capture(session))
        assertEquals(1, calls)
    }

    @Test
    fun `stale manual token cannot consume the newer active session`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var calls = 0
        gateway.attachManual {
            calls += 1
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW)
        }
        val stale = gateway.beginManualCaptureSession()
        val current = gateway.beginManualCaptureSession()

        assertEquals(CaptureResult.RequiresSystemConsent, gateway.capture(stale))
        assertEquals(CaptureResult.Failure(CaptureFailure.SECURE_WINDOW), gateway.capture(current))
        assertEquals(1, calls)
    }

    @Test
    fun `manual capture session expires without invoking provider`() = runTest {
        var elapsed = 1_000L
        val gateway = AccessibilityCaptureGateway(elapsedRealtimeMillis = { elapsed })
        var calls = 0
        gateway.attachManual {
            calls += 1
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
        val session = gateway.beginManualCaptureSession()
        elapsed += 2_001L

        assertEquals(CaptureResult.RequiresSystemConsent, gateway.capture(session))
        assertEquals(0, calls)
    }

    @Test
    fun `automatic capture cannot consume armed manual session`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var automaticCalls = 0
        var manualCalls = 0
        gateway.attach {
            automaticCalls += 1
            CaptureResult.Failure(CaptureFailure.TOO_FREQUENT)
        }
        gateway.attachManual {
            manualCalls += 1
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW)
        }
        val session = gateway.beginManualCaptureSession()

        assertEquals(CaptureResult.Failure(CaptureFailure.TOO_FREQUENT), gateway.capture())
        assertEquals(CaptureResult.Failure(CaptureFailure.SECURE_WINDOW), gateway.capture(session))
        assertEquals(1, automaticCalls)
        assertEquals(1, manualCalls)
    }

    @Test
    fun `detach invalidates manual sessions and providers`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var calls = 0
        gateway.attachManual {
            calls += 1
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
        val session = gateway.beginManualCaptureSession()

        gateway.detach()

        assertEquals(CaptureResult.RequiresSystemConsent, gateway.capture(session))
        assertEquals(0, calls)
    }

    @Test
    fun `framework screenshot errors map only to redacted failure enums`() {
        assertEquals(
            CaptureFailure.SECURE_WINDOW,
            mapScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW),
        )
        assertEquals(
            CaptureFailure.TOO_FREQUENT,
            mapScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT),
        )
        assertEquals(
            CaptureFailure.NO_ACCESSIBILITY_ACCESS,
            mapScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS),
        )
        assertEquals(
            CaptureFailure.INVALID_DISPLAY,
            mapScreenshotError(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY),
        )
        assertEquals(CaptureFailure.INTERNAL, mapScreenshotError(Int.MIN_VALUE))
    }

    @Test
    fun `detached gateway requires system consent without invoking old provider`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var calls = 0
        gateway.attach {
            calls += 1
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }
        gateway.detach()

        assertEquals(CaptureResult.RequiresSystemConsent, gateway.capture())
        assertEquals(0, calls)
    }

    @Test
    fun `attach replaces provider and each capture invokes current provider once`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var firstCalls = 0
        var secondCalls = 0
        gateway.attach {
            firstCalls += 1
            CaptureResult.Failure(CaptureFailure.SECURE_WINDOW)
        }
        gateway.attach {
            secondCalls += 1
            CaptureResult.Failure(CaptureFailure.TOO_FREQUENT)
        }

        assertEquals(CaptureResult.Failure(CaptureFailure.TOO_FREQUENT), gateway.capture())
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `concurrent capture calls are serialized`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        var active = 0
        var maximumActive = 0
        var calls = 0
        gateway.attach {
            calls += 1
            active += 1
            maximumActive = maxOf(maximumActive, active)
            delay(10)
            active -= 1
            CaptureResult.Failure(CaptureFailure.INTERNAL)
        }

        List(3) { async { gateway.capture() } }.awaitAll()

        assertEquals(3, calls)
        assertEquals(1, maximumActive)
    }

    @Test
    fun `provider failures are redacted while cancellation propagates`() = runTest {
        val gateway = AccessibilityCaptureGateway()
        gateway.attach { error("raw framework error") }

        assertEquals(CaptureResult.Failure(CaptureFailure.INTERNAL), gateway.capture())

        gateway.attach { throw CancellationException("cancel") }
        val thrown = try {
            gateway.capture()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue(thrown is CancellationException)
    }
}
