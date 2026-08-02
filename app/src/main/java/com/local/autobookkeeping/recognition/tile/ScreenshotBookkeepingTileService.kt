package com.local.autobookkeeping.recognition.tile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import com.local.autobookkeeping.AutoBookkeepingApplication
import com.local.autobookkeeping.recognition.capture.ManualCaptureSession

internal const val EXTRA_MANUAL_CAPTURE_SESSION = "manual_capture_session"

internal interface TileActivityHost {
    fun startAndCollapse(intent: Intent)
    fun startAndCollapse(pendingIntent: PendingIntent, flags: Int)
}

internal object TileCaptureActivityLauncher {
    fun launch(
        host: TileActivityHost,
        context: Context,
        session: ManualCaptureSession,
        sdkInt: Int,
    ) {
        val intent = Intent(context, ScreenshotCaptureActivity::class.java)
            .putExtra(EXTRA_MANUAL_CAPTURE_SESSION, session.token)
            .setData(
                Uri.Builder()
                    .scheme(context.packageName)
                    .authority("screenshot-capture")
                    .appendPath(session.token.toString())
                    .build(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags = PendingIntent.FLAG_ONE_SHOT or
                PendingIntent.FLAG_CANCEL_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
            host.startAndCollapse(
                PendingIntent.getActivity(context, session.requestCode(), intent, flags),
                flags,
            )
        } else {
            host.startAndCollapse(intent)
        }
    }

    private fun ManualCaptureSession.requestCode(): Int =
        (token xor (token ushr Int.SIZE_BITS)).toInt()
}

class ScreenshotBookkeepingTileService : TileService(), TileActivityHost {
    override fun onClick() {
        super.onClick()
        val graph = (application as? AutoBookkeepingApplication)?.applicationGraph ?: return
        val session = graph.accessibilityCaptureGateway.beginManualCaptureSession()
        TileCaptureActivityLauncher.launch(this, this, session, Build.VERSION.SDK_INT)
    }

    override fun startAndCollapse(intent: Intent) {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }

    override fun startAndCollapse(pendingIntent: PendingIntent, flags: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pendingIntent)
        }
    }
}
