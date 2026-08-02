package com.local.autobookkeeping.recognition.tile

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectionAndroidContractsTest {
    @Test
    fun `RGBA copy honors pixel and row stride and crops padding to screen size`() {
        val width = 2
        val height = 2
        val pixelStride = 8
        val rowStride = 24
        val bytes = ByteBuffer.allocate(rowStride * height)
        putRgba(bytes, 0, 255, 0, 0, 255)
        putRgba(bytes, pixelStride, 0, 255, 0, 128)
        putRgba(bytes, rowStride, 0, 0, 255, 255)
        putRgba(bytes, rowStride + pixelStride, 255, 255, 255, 64)

        val bitmap = RgbaPlaneBitmapCopier.copy(
            buffer = bytes,
            pixelStride = pixelStride,
            rowStride = rowStride,
            width = width,
            height = height,
        )

        assertEquals(2, bitmap.width)
        assertEquals(2, bitmap.height)
        assertEquals(BitmapConfigName.ARGB_8888, BitmapConfigName.of(bitmap))
        assertEquals(Color.argb(255, 255, 0, 0), bitmap.getPixel(0, 0))
        assertEquals(Color.argb(128, 0, 255, 0), bitmap.getPixel(1, 0))
        assertEquals(Color.argb(255, 0, 0, 255), bitmap.getPixel(0, 1))
        assertEquals(Color.argb(64, 255, 255, 255), bitmap.getPixel(1, 1))
        bitmap.recycle()
    }

    @Test
    fun `invalid RGBA layout is rejected without allocating a bitmap`() {
        val result = runCatching {
            RgbaPlaneBitmapCopier.copy(
                buffer = ByteBuffer.allocate(7),
                pixelStride = 4,
                rowStride = 8,
                width = 2,
                height = 1,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `RGBA copy respects the plane buffer starting position`() {
        val bytes = ByteBuffer.allocate(12)
        putRgba(bytes, 4, 17, 34, 51, 68)
        bytes.position(4)

        val bitmap = RgbaPlaneBitmapCopier.copy(
            buffer = bytes,
            pixelStride = 4,
            rowStride = 4,
            width = 1,
            height = 1,
        )

        assertEquals(Color.argb(68, 17, 34, 51), bitmap.getPixel(0, 0))
        bitmap.recycle()
    }

    @Test
    fun `projection notification is immediate private local only and ongoing`() {
        val context = RuntimeEnvironment.getApplication()

        val notification = ProjectionForegroundNotification.create(
            context = context,
            channelName = "截屏识别",
            title = "正在识别当前页面",
        )

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("正在识别当前页面", notification.extras.getString(Notification.EXTRA_TITLE))
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(ProjectionForegroundNotification.CHANNEL_ID)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canShowBadge())
    }

    @Test
    fun `service start always foregrounds first and restart without grant does no capture`() {
        val events = mutableListOf<String>()
        val coordinator = ProjectionServiceStartCoordinator(
            startForeground = { events += "foreground" },
            startCapture = { events += "capture" },
            finish = { events += "finish" },
        )

        val result = coordinator.onStart(null)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(listOf("foreground", "finish"), events)
    }

    @Test
    fun `service accepts only one grant and failed start still removes foreground`() {
        val grant = ProjectionGrant(Activity.RESULT_OK, Intent("one-shot"))
        val events = mutableListOf<String>()
        val coordinator = ProjectionServiceStartCoordinator(
            startForeground = { events += "foreground" },
            startCapture = {
                events += "capture"
                error("redacted framework failure")
            },
            finish = { events += "finish" },
        )

        assertEquals(Service.START_NOT_STICKY, coordinator.onStart(grant))
        assertEquals(Service.START_NOT_STICKY, coordinator.onStart(grant))

        assertEquals(listOf("foreground", "capture", "finish", "foreground"), events)
    }

    private fun putRgba(
        buffer: ByteBuffer,
        offset: Int,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
    ) {
        buffer.put(offset, red.toByte())
        buffer.put(offset + 1, green.toByte())
        buffer.put(offset + 2, blue.toByte())
        buffer.put(offset + 3, alpha.toByte())
    }

    private enum class BitmapConfigName {
        ARGB_8888;

        companion object {
            fun of(bitmap: android.graphics.Bitmap): BitmapConfigName =
                valueOf(requireNotNull(bitmap.config).name)
        }
    }
}
