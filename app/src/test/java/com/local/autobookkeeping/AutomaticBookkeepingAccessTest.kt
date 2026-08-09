package com.local.autobookkeeping

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutomaticBookkeepingAccessTest {
    @Test
    fun `notification launcher adds new task flag for application context`() {
        val application = RuntimeEnvironment.getApplication()

        AndroidNotificationSettingsLauncher(application).open(GENERAL_SETTINGS_ACTION)

        val startedIntent = shadowOf(application).nextStartedActivity
        assertEquals(GENERAL_SETTINGS_ACTION, startedIntent.action)
        assertTrue(startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `accessibility access recognizes fully qualified payment service`() {
        assertTrue(
            AccessibilityAccess.isEnabled(EXPECTED_SERVICE) {
                listOf(serviceInfo(EXPECTED_SERVICE.className))
            },
        )
    }

    @Test
    fun `accessibility access normalizes relative payment service name`() {
        assertTrue(
            AccessibilityAccess.isEnabled(EXPECTED_SERVICE) {
                listOf(
                    serviceInfo(
                        ".recognition.accessibility.PaymentPageAccessibilityService",
                    ),
                )
            },
        )
    }

    @Test
    fun `accessibility access ignores service with null resolve info`() {
        assertFalse(
            AccessibilityAccess.isEnabled(EXPECTED_SERVICE) {
                listOf(null)
            },
        )
    }

    @Test
    fun `accessibility access preserves exact component identity`() {
        assertFalse(
            AccessibilityAccess.isEnabled(EXPECTED_SERVICE) {
                listOf(
                    serviceInfo(
                        "com.local.autobookkeeping.recognition.accessibility.UnrelatedService",
                    ),
                    serviceInfo(
                        EXPECTED_SERVICE.className,
                        packageName = "com.local.autobookkeeping.preview",
                    ),
                )
            },
        )
    }

    @Test
    fun `accessibility access maps service query security exception to false`() {
        assertFalse(
            AccessibilityAccess.isEnabled(EXPECTED_SERVICE) {
                throw SecurityException("restricted accessibility state")
            },
        )
    }

    @Test
    fun `accessibility access maps service query runtime exception to false`() {
        assertFalse(
            AccessibilityAccess.isEnabled(EXPECTED_SERVICE) {
                throw IllegalStateException("broken accessibility manager")
            },
        )
    }

    private fun serviceInfo(
        name: String,
        packageName: String = EXPECTED_SERVICE.packageName,
    ): ResolveInfo = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            this.name = name
        }
    }

    private companion object {
        val EXPECTED_SERVICE = ComponentName(
            "com.local.autobookkeeping",
            "com.local.autobookkeeping.recognition.accessibility.PaymentPageAccessibilityService",
        )
    }
}
