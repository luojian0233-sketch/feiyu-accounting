package com.local.autobookkeeping

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchManifestContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val packageManager = context.packageManager

    @Test
    fun onlyPayloadFreeLauncherIsExported() {
        assertTrue(activityInfo(LauncherActivity::class.java).exported)
        assertFalse(activityInfo(MainActivity::class.java).exported)
        assertFalse(activityInfo(RecognitionReviewLaunchActivity::class.java).exported)

        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val resolved = packageManager.queryIntentActivities(launcher, 0)
        assertEquals(
            listOf(LauncherActivity::class.java.name),
            resolved.map { it.activityInfo.name },
        )
    }

    private fun activityInfo(activityClass: Class<*>) = packageManager.getActivityInfo(
        ComponentName(context, activityClass),
        PackageManager.ComponentInfoFlags.of(0),
    )
}
