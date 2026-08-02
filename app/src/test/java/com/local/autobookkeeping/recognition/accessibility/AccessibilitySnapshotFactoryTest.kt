package com.local.autobookkeeping.recognition.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilitySnapshotFactoryTest {
    @Suppress("DEPRECATION")
    @Test
    fun `visible node text keeps screen geometry in snapshot`() {
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.tencent.mm"
            text = "支付成功"
            setBoundsInScreen(Rect(12, 34, 212, 84))
            isVisibleToUser = true
        }

        val snapshot = AccessibilitySnapshotFactory.create(
            root = root,
            packageName = "com.tencent.mm",
            observedAt = Instant.parse("2026-07-29T10:00:00Z"),
        )

        assertEquals("com.tencent.mm", snapshot.packageName)
        assertEquals("支付成功", snapshot.elements.single().text)
        assertEquals(12, snapshot.elements.single().bounds.left)
        assertEquals(34, snapshot.elements.single().bounds.top)
        assertEquals(212, snapshot.elements.single().bounds.right)
        assertEquals(84, snapshot.elements.single().bounds.bottom)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `factory traverses descendants and excludes invisible node text`() {
        val root = AccessibilityNodeInfo.obtain().apply {
            text = "不可见原文"
            isVisibleToUser = false
        }
        val child = AccessibilityNodeInfo.obtain().apply {
            text = "支付成功"
            setBoundsInScreen(Rect(10, 20, 110, 50))
            isVisibleToUser = true
        }
        shadowOf(root).addChild(child)

        val snapshot = AccessibilitySnapshotFactory.create(
            root = root,
            packageName = "com.tencent.mm",
            observedAt = Instant.parse("2026-07-29T10:00:00Z"),
        )

        assertEquals(listOf("支付成功"), snapshot.elements.map { it.text })
    }
}
