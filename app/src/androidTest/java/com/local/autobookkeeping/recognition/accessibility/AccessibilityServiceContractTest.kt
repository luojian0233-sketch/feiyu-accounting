package com.local.autobookkeeping.recognition.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class AccessibilityServiceContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceDeclarationIsRestrictedToAccessibilityBinding() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, PaymentPageAccessibilityService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertTrue(service.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, service.permission)
        assertNotEquals(0, service.metaData.getInt("android.accessibilityservice"))
    }

    @Test
    fun serviceConfigurationIsReadOnlyAndPackageScoped() {
        context.resources.getXml(R.xml.payment_page_accessibility_service).use { parser ->
            while (parser.eventType != XmlPullParser.START_TAG) parser.next()
            val androidNamespace = "http://schemas.android.com/apk/res/android"

            assertEquals(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                parser.getAttributeIntValue(androidNamespace, "accessibilityEventTypes", 0),
            )
            assertEquals(
                "com.tencent.mm,com.eg.android.AlipayGphone",
                parser.getAttributeValue(androidNamespace, "packageNames"),
            )
            assertTrue(
                parser.getAttributeBooleanValue(androidNamespace, "canRetrieveWindowContent", false),
            )
            assertTrue(parser.getAttributeBooleanValue(androidNamespace, "canTakeScreenshot", false))
            assertFalse(parser.getAttributeBooleanValue(androidNamespace, "canPerformGestures", false))
            assertEquals(
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS,
                parser.getAttributeIntValue(androidNamespace, "accessibilityFlags", 0),
            )
            assertEquals(
                AccessibilityServiceInfo.FEEDBACK_GENERIC,
                parser.getAttributeIntValue(androidNamespace, "accessibilityFeedbackType", 0),
            )
            assertEquals(250, parser.getAttributeIntValue(androidNamespace, "notificationTimeout", 0))
        }
    }
}
