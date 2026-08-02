package com.local.autobookkeeping.recognition.tile

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotTileContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val packageManager = context.packageManager

    @Test
    fun tileServiceIsSystemBoundExportedAndDiscoverableWithLocalizedArtwork() {
        val component = ComponentName(context, ScreenshotBookkeepingTileService::class.java)
        val info = packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)

        assertTrue(info.exported)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, info.permission)
        assertEquals("截屏记账", info.loadLabel(packageManager).toString())
        assertEquals(R.drawable.ic_screenshot_bookkeeping, info.iconResource)
        val discovered = packageManager.queryIntentServices(
            Intent(TileService.ACTION_QS_TILE).setPackage(context.packageName),
            0,
        )
        assertTrue(discovered.any { it.serviceInfo.name == component.className })
    }

    @Test
    fun captureActivityIsPrivateAndUsesTransparentNoAnimationTheme() {
        val info = packageManager.getActivityInfo(
            ComponentName(context, ScreenshotCaptureActivity::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(info.exported)
        assertEquals(R.style.Theme_AutoBookkeeping_ScreenshotCapture, info.themeResource)
    }
}
