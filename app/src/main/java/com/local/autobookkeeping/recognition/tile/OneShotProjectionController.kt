package com.local.autobookkeeping.recognition.tile

import android.content.Intent
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ProjectionGrant(
    val resultCode: Int,
    val data: Intent,
)

internal fun interface ProjectionResourceFactory {
    fun open(grant: ProjectionGrant): ProjectionCaptureResources
}

internal interface ProjectionCaptureResources {
    fun registerProjectionCallback(onStop: () -> Unit)
    fun setImageListener(onImage: () -> Unit, onError: () -> Unit)
    fun createVirtualDisplay()
    fun acquireLatestImage(): ProjectionFrame?
    fun releaseVirtualDisplay()
    fun closeImageReader()
    fun unregisterProjectionCallback()
    fun stopProjection()
    fun quitHandlerThread()
}

internal interface ProjectionFrame : AutoCloseable {
    fun copyToSoftwareBitmap(): Bitmap
}

internal fun interface ProjectionTimeoutScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): ProjectionTimeout
}

internal fun interface ProjectionTimeout {
    fun cancel()
}

internal class OneShotProjectionController(
    private val resourceFactory: ProjectionResourceFactory,
    private val process: suspend (Bitmap) -> Unit,
    private val scope: CoroutineScope,
    private val timeoutScheduler: ProjectionTimeoutScheduler,
    private val timeoutMillis: Long,
    private val onFinished: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val claimedImage = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val resources = AtomicReference<ProjectionCaptureResources?>()
    private val timeout = AtomicReference<ProjectionTimeout?>()
    private val processingJob = AtomicReference<Job?>()

    fun start(grant: ProjectionGrant): Boolean {
        if (!started.compareAndSet(false, true)) return false
        return try {
            val opened = resourceFactory.open(grant)
            resources.set(opened)
            val scheduled = timeoutScheduler.schedule(timeoutMillis, ::finish)
            timeout.set(scheduled)
            if (finished.get()) scheduled.cancelSafely()
            if (!finished.get()) opened.registerProjectionCallback(::finish)
            if (!finished.get()) opened.setImageListener(::onImageAvailable, ::finish)
            if (!finished.get()) opened.createVirtualDisplay()
            !finished.get()
        } catch (_: Exception) {
            finish()
            false
        }
    }

    fun stop() {
        finish()
    }

    private fun onImageAvailable() {
        val activeResources = resources.get() ?: return
        val image = try {
            activeResources.acquireLatestImage()
        } catch (_: Exception) {
            finish()
            return
        } ?: return
        if (finished.get() || !claimedImage.compareAndSet(false, true)) {
            image.closeSafely()
            return
        }
        val bitmap = AtomicReference<Bitmap?>()
        val released = AtomicBoolean(false)
        fun releaseFrame() {
            if (!released.compareAndSet(false, true)) return
            image.closeSafely()
            bitmap.getAndSet(null)?.recycleSafely()
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val copied = image.copyToSoftwareBitmap()
                bitmap.set(copied)
                process(copied)
            } catch (_: Exception) {
                Unit
            } finally {
                releaseFrame()
                finish()
            }
        }
        processingJob.set(job)
        job.invokeOnCompletion {
            releaseFrame()
            finish()
        }
        if (finished.get()) {
            job.cancel()
        } else {
            job.start()
        }
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        timeout.getAndSet(null)?.cancelSafely()
        processingJob.getAndSet(null)?.cancel()
        resources.getAndSet(null)?.let { opened ->
            runSafely(opened::releaseVirtualDisplay)
            runSafely(opened::closeImageReader)
            runSafely(opened::unregisterProjectionCallback)
            runSafely(opened::stopProjection)
            runSafely(opened::quitHandlerThread)
        }
        runSafely(onFinished)
    }

    private fun ProjectionTimeout.cancelSafely() = runSafely(::cancel)
    private fun ProjectionFrame.closeSafely() = runSafely(::close)

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) runSafely(::recycle)
    }

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            Unit
        }
    }
}
