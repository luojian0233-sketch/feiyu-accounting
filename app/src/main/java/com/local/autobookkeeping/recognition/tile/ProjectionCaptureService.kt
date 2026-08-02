package com.local.autobookkeeping.recognition.tile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.local.autobookkeeping.AutoBookkeepingApplication
import com.local.autobookkeeping.R
import java.time.Instant
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ProjectionCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopped = AtomicBoolean(false)
    private var controller: OneShotProjectionController? = null
    private val startCoordinator by lazy {
        ProjectionServiceStartCoordinator(
            startForeground = ::startPrivateForegroundNotification,
            startCapture = ::startCapture,
            finish = ::finishService,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return startCoordinator.onStart(intent?.projectionGrant())
    }

    private fun startCapture(grant: ProjectionGrant) {
        val graph = (application as? AutoBookkeepingApplication)?.applicationGraph
            ?: error("Application graph unavailable")
        val created = OneShotProjectionController(
            resourceFactory = AndroidProjectionResourceFactory(this),
            process = { bitmap ->
                graph.screenshotRecognitionController.process(bitmap, null, Instant.now())
            },
            scope = serviceScope,
            timeoutScheduler = HandlerProjectionTimeoutScheduler(Handler(Looper.getMainLooper())),
            timeoutMillis = CAPTURE_TIMEOUT_MILLIS,
            onFinished = ::finishService,
        )
        controller = created
        created.start(grant)
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        serviceScope.cancel()
        removeForegroundNotification()
        super.onDestroy()
    }

    private fun startPrivateForegroundNotification() {
        val notification = ProjectionForegroundNotification.create(
            context = this,
            channelName = getString(R.string.projection_notification_channel),
            title = getString(R.string.projection_notification_title),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun finishService() {
        if (!stopped.compareAndSet(false, true)) return
        controller = null
        removeForegroundNotification()
        stopSelf()
        serviceScope.cancel()
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun Intent.projectionGrant(): ProjectionGrant? {
        val resultCode = getIntExtra(EXTRA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
        if (resultCode != android.app.Activity.RESULT_OK) return null
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_PROJECTION_DATA)
        } ?: return null
        return ProjectionGrant(resultCode, data)
    }

    companion object {
        private const val EXTRA_PROJECTION_RESULT_CODE = "projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"
        private const val NOTIFICATION_ID = 42011
        private const val CAPTURE_TIMEOUT_MILLIS = 5_000L

        internal fun createIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ProjectionCaptureService::class.java)
                .putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data)
    }
}

internal class ProjectionServiceStartCoordinator(
    private val startForeground: () -> Unit,
    private val startCapture: (ProjectionGrant) -> Unit,
    private val finish: () -> Unit,
) {
    private val accepted = AtomicBoolean(false)

    fun onStart(grant: ProjectionGrant?): Int {
        if (!accepted.compareAndSet(false, true)) return Service.START_NOT_STICKY
        startForeground()
        if (grant == null) {
            finish()
            return Service.START_NOT_STICKY
        }
        try {
            startCapture(grant)
        } catch (_: Exception) {
            finish()
        }
        return Service.START_NOT_STICKY
    }
}

internal object ProjectionForegroundNotification {
    const val CHANNEL_ID = "one_frame_projection"

    fun create(context: Context, channelName: String, title: String): Notification {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenshot_bookkeeping)
            .setContentTitle(title)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }
}

private class HandlerProjectionTimeoutScheduler(
    private val handler: Handler,
) : ProjectionTimeoutScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): ProjectionTimeout {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return ProjectionTimeout { handler.removeCallbacks(runnable) }
    }
}

private class AndroidProjectionResourceFactory(
    private val context: Context,
) : ProjectionResourceFactory {
    override fun open(grant: ProjectionGrant): ProjectionCaptureResources {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(grant.resultCode, grant.data)
            ?: error("Projection unavailable")
        var handlerThread: HandlerThread? = null
        var imageReader: ImageReader? = null
        return try {
            val size = context.captureSize()
            val createdThread = HandlerThread("one-frame-projection").also { it.start() }
            handlerThread = createdThread
            val createdReader = ImageReader.newInstance(
                size.width,
                size.height,
                PixelFormat.RGBA_8888,
                MAX_IMAGES,
            )
            imageReader = createdReader
            AndroidProjectionCaptureResources(
                projection = projection,
                imageReader = createdReader,
                handlerThread = createdThread,
                size = size,
            )
        } catch (failure: Exception) {
            try {
                imageReader?.close()
            } catch (_: Exception) {
                Unit
            }
            try {
                projection.stop()
            } catch (_: Exception) {
                Unit
            }
            try {
                handlerThread?.quitSafely()
            } catch (_: Exception) {
                Unit
            }
            throw failure
        }
    }

    private companion object {
        const val MAX_IMAGES = 2
    }
}

private class AndroidProjectionCaptureResources(
    private val projection: MediaProjection,
    private val imageReader: ImageReader,
    private val handlerThread: HandlerThread,
    private val size: CaptureSize,
) : ProjectionCaptureResources {
    private val handler = Handler(handlerThread.looper)
    @Volatile private var projectionCallback: MediaProjection.Callback? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null

    override fun registerProjectionCallback(onStop: () -> Unit) {
        val callback = object : MediaProjection.Callback() {
            override fun onStop() = onStop()
        }
        projectionCallback = callback
        projection.registerCallback(callback, handler)
    }

    override fun setImageListener(onImage: () -> Unit, onError: () -> Unit) {
        imageReader.setOnImageAvailableListener(
            {
                try {
                    onImage()
                } catch (_: Exception) {
                    onError()
                }
            },
            handler,
        )
    }

    override fun createVirtualDisplay() {
        check(virtualDisplay == null)
        virtualDisplay = projection.createVirtualDisplay(
            "one-frame-capture",
            size.width,
            size.height,
            size.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        ) ?: error("Virtual display unavailable")
    }

    override fun acquireLatestImage(): ProjectionFrame? =
        imageReader.acquireLatestImage()?.let { image ->
            AndroidProjectionFrame(image, size.width, size.height)
        }

    override fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
    }

    override fun closeImageReader() {
        imageReader.setOnImageAvailableListener(null, null)
        imageReader.close()
    }

    override fun unregisterProjectionCallback() {
        projectionCallback?.let(projection::unregisterCallback)
        projectionCallback = null
    }

    override fun stopProjection() {
        projection.stop()
    }

    override fun quitHandlerThread() {
        handlerThread.quitSafely()
    }
}

private class AndroidProjectionFrame(
    private val image: Image,
    private val width: Int,
    private val height: Int,
) : ProjectionFrame {
    override fun copyToSoftwareBitmap(): Bitmap {
        val plane = image.planes.singleOrNull() ?: error("Unexpected image plane count")
        return RgbaPlaneBitmapCopier.copy(
            buffer = plane.buffer,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride,
            width = width,
            height = height,
        )
    }

    override fun close() = image.close()
}

internal object RgbaPlaneBitmapCopier {
    private const val RGBA_BYTES = 4

    fun copy(
        buffer: ByteBuffer,
        pixelStride: Int,
        rowStride: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        require(width > 0 && height > 0)
        require(pixelStride >= RGBA_BYTES)
        require(rowStride.toLong() >= width.toLong() * pixelStride)
        val readable = buffer.duplicate()
        val baseOffset = readable.position()
        val lastByte = baseOffset.toLong() +
            (height - 1L) * rowStride +
            (width - 1L) * pixelStride +
            RGBA_BYTES
        require(lastByte <= readable.limit().toLong())
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowStart = baseOffset + y * rowStride
            for (x in 0 until width) {
                val offset = rowStart + x * pixelStride
                val red = readable.get(offset).toInt() and 0xff
                val green = readable.get(offset + 1).toInt() and 0xff
                val blue = readable.get(offset + 2).toInt() and 0xff
                val alpha = readable.get(offset + 3).toInt() and 0xff
                pixels[y * width + x] = Color.argb(alpha, red, green, blue)
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (failure: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw failure
        }
    }

}

private data class CaptureSize(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

private fun Context.captureSize(): CaptureSize {
    val windowManager = getSystemService(WindowManager::class.java)
    val displayMetrics = resources.displayMetrics
    val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.maximumWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics).let { metrics ->
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }
    return CaptureSize(
        width = bounds.width().coerceAtLeast(1),
        height = bounds.height().coerceAtLeast(1),
        densityDpi = displayMetrics.densityDpi.coerceAtLeast(1),
    )
}
