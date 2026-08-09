package com.local.autobookkeeping

import android.content.ComponentName
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticBookkeepingAccessTest {
    @Test
    fun `accessibility access requires exact payment service component`() {
        val expected = ComponentName(
            "com.local.autobookkeeping",
            "com.local.autobookkeeping.recognition.accessibility.PaymentPageAccessibilityService",
        )

        assertFalse(
            AccessibilityAccess.isEnabled(
                expectedService = expected,
                enabledServices = listOf(
                    ComponentName(
                        "com.local.autobookkeeping",
                        "com.local.autobookkeeping.recognition.accessibility.UnrelatedService",
                    ),
                    ComponentName(
                        "com.local.autobookkeeping.preview",
                        "com.local.autobookkeeping.recognition.accessibility.PaymentPageAccessibilityService",
                    ),
                ),
            ),
        )
        assertTrue(
            AccessibilityAccess.isEnabled(
                expectedService = expected,
                enabledServices = listOf(expected),
            ),
        )
    }
}
