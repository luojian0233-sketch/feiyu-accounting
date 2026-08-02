package com.local.autobookkeeping.recognition.tile

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.Intent
import com.local.autobookkeeping.recognition.capture.ManualCaptureSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickSettingsTileLauncherTest {
    @Test
    fun `pre API 33 returns manual instructions without requesting system tile add`() {
        var calls = 0
        val launcher = QuickSettingsTileLauncher(
            sdkInt = 32,
            requester = TileAddRequester {
                calls += 1
            },
        )

        val result = launcher.requestAdd()

        assertEquals(TileAddResult.ManualInstructions, result)
        assertEquals(0, calls)
    }

    @Test
    fun `API 33 tile add result codes map exactly`() {
        val cases = listOf(
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED to TileAddResult.Requested,
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED to TileAddResult.AlreadyAdded,
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED to TileAddResult.Failed,
            Int.MIN_VALUE to TileAddResult.Failed,
        )

        cases.forEach { (status, expected) ->
            val callbacks = mutableListOf<TileAddResult>()
            val launcher = QuickSettingsTileLauncher(
                sdkInt = 33,
                requester = TileAddRequester { callback -> callback(status) },
            )

            val immediate = launcher.requestAdd(callbacks::add)

            assertEquals(expected, immediate)
            assertEquals(listOf(expected), callbacks)
        }
    }

    @Test
    fun `API 33 unavailable or throwing status bar service fails safely`() {
        assertEquals(
            TileAddResult.Failed,
            QuickSettingsTileLauncher(sdkInt = 33, requester = null).requestAdd(),
        )
        val launcher = QuickSettingsTileLauncher(
            sdkInt = 33,
            requester = TileAddRequester { throw SecurityException("system rejected request") },
        )

        assertEquals(TileAddResult.Failed, launcher.requestAdd())
    }

    @Test
    fun `API 34 tile click uses immutable pending intent capture path`() {
        val host = RecordingTileActivityHost()
        val context = RuntimeEnvironment.getApplication()
        val session = ManualCaptureSession(41L)

        TileCaptureActivityLauncher.launch(host, context, session, sdkInt = 34)

        assertNotNull(host.pendingIntent)
        assertEquals(null, host.intent)
        val saved = shadowOf(host.pendingIntent).savedIntent
        assertEquals(ScreenshotCaptureActivity::class.java.name, saved.component?.className)
        assertEquals(41L, saved.getLongExtra(EXTRA_MANUAL_CAPTURE_SESSION, -1L))
        assertTrue(saved.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(saved.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
        assertTrue(host.pendingIntentFlags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(host.pendingIntentFlags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }

    @Test
    fun `pre API 34 tile click uses legacy intent capture path`() {
        val host = RecordingTileActivityHost()
        val context = RuntimeEnvironment.getApplication()

        TileCaptureActivityLauncher.launch(
            host,
            context,
            ManualCaptureSession(42L),
            sdkInt = 33,
        )

        assertNotNull(host.intent)
        assertEquals(null, host.pendingIntent)
        assertEquals(ScreenshotCaptureActivity::class.java.name, host.intent?.component?.className)
        assertEquals(42L, host.intent?.getLongExtra(EXTRA_MANUAL_CAPTURE_SESSION, -1L))
        assertFalse(host.intent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0)
        assertTrue(host.intent!!.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
    }

    private class RecordingTileActivityHost : TileActivityHost {
        var intent: Intent? = null
        var pendingIntent: PendingIntent? = null
        var pendingIntentFlags: Int = 0

        override fun startAndCollapse(intent: Intent) {
            this.intent = intent
        }

        override fun startAndCollapse(pendingIntent: PendingIntent, flags: Int) {
            this.pendingIntent = pendingIntent
            pendingIntentFlags = flags
        }
    }
}
