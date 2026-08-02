package com.local.autobookkeeping.recognition.tile

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectionServiceContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val packageManager = context.packageManager

    @Test
    fun projectionServiceIsPrivateAndDeclaresProjectionForegroundContract() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            packageManager.checkPermission(Manifest.permission.FOREGROUND_SERVICE, context.packageName),
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            packageManager.checkPermission(
                Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                context.packageName,
            ),
        )
        val info = packageManager.getServiceInfo(
            ComponentName(context, ProjectionCaptureService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertFalse(info.exported)
        assertTrue(
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0,
        )
    }
}
