package com.local.autobookkeeping.recognition.tile

import android.content.Intent
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    private val state = AtomicReference(CaptureState.NEW)
    private val resources = AtomicReference<ProjectionCaptureResources?>()
    private val resourcesClosed = AtomicBoolean(false)
    private val timeout = AtomicReference<ProjectionTimeout?>()
    private val processingJob = AtomicReference<Job?>()
    private val finishedCallbackSent = AtomicBoolean(false)
    private val pendingSetupFrame = AtomicReference<ProjectionFrame?>()
    private val setupOwnershipLock = Any()

    fun start(grant: ProjectionGrant): Boolean {
        if (!state.compareAndSet(CaptureState.NEW, CaptureState.SETTING_UP)) return false
        var readyToWait = false
        try {
            val opened = resourceFactory.open(grant)
            resources.set(opened)
            if (setupStopIsPending()) return finishSetupWithoutWaiting()
            opened.registerProjectionCallback(::stop)
            if (setupStopIsPending()) return finishSetupWithoutWaiting()
            opened.setImageListener(
                onImage = { onImageAvailable(opened) },
                onError = ::stop,
            )
            if (setupStopIsPending()) return finishSetupWithoutWaiting()
            opened.createVirtualDisplay()
            var pendingFrame: ProjectionFrame? = null
            val setupCompleted = synchronized(setupOwnershipLock) {
                if (state.get() != CaptureState.SETTING_UP) {
                    false
                } else {
                    pendingFrame = pendingSetupFrame.getAndSet(null)
                    state.set(
                        if (pendingFrame == null) CaptureState.WAITING else CaptureState.COPYING,
                    )
                    true
                }
            }
            if (setupCompleted) {
                readyToWait = true
                val claimedPendingFrame = pendingFrame
                if (claimedPendingFrame == null) {
                    armWaitingTimeout()
                } else {
                    copyClaimedFrame(claimedPendingFrame)
                }
            }
        } catch (_: Exception) {
            return finishSetupWithoutWaiting()
        } finally {
            if (!readyToWait && setupIsInProgress()) finishSetupWithoutWaiting()
        }
        return readyToWait
    }

    fun stop() {
        while (true) {
            when (val current = state.get()) {
                CaptureState.WAITING,
                -> if (state.compareAndSet(current, CaptureState.FINISHED)) {
                    cancelWaitingTimeout()
                    closeCaptureResources()
                    notifyFinished()
                    return
                }
                CaptureState.COPYING ->
                    if (state.compareAndSet(current, CaptureState.STOP_DURING_COPY)) return
                CaptureState.PROCESSING ->
                    if (state.compareAndSet(current, CaptureState.STOP_DURING_PROCESSING)) {
                        processingJob.get()?.cancel()
                        return
                    }
                CaptureState.STOP_DURING_COPY -> return
                CaptureState.STOP_DURING_PROCESSING -> {
                    processingJob.get()?.cancel()
                    return
                }
                CaptureState.NEW -> if (state.compareAndSet(current, CaptureState.FINISHED)) {
                    notifyFinished()
                    return
                }
                CaptureState.SETTING_UP -> {
                    if (requestSetupStop()) return
                }
                CaptureState.SETUP_STOP_PENDING -> return
                CaptureState.FINISHED -> return
            }
        }
    }

    private fun onWaitingTimeout() {
        if (state.get() != CaptureState.WAITING) return
        if (!state.compareAndSet(CaptureState.WAITING, CaptureState.FINISHED)) return
        cancelWaitingTimeout()
        closeCaptureResources()
        notifyFinished()
    }

    private fun onImageAvailable(activeResources: ProjectionCaptureResources) {
        val image = try {
            activeResources.acquireLatestImage()
        } catch (_: Exception) {
            if (state.get() == CaptureState.WAITING) stop()
            return
        } ?: return
        onFrameAvailable(image)
    }

    private fun onFrameAvailable(image: ProjectionFrame) {
        when (claimFrame(image)) {
            FrameClaim.STASHED -> Unit
            FrameClaim.CLAIMED -> copyClaimedFrame(image)
            FrameClaim.CLOSE -> image.closeSafely()
        }
    }

    private fun copyClaimedFrame(image: ProjectionFrame) {
        cancelWaitingTimeout()
        var bitmap: Bitmap? = null
        try {
            bitmap = image.copyToSoftwareBitmap()
        } catch (_: Exception) {
            Unit
        } finally {
            image.closeSafely()
            closeCaptureResources()
        }
        if (bitmap == null) {
            completeWithoutProcessing()
            return
        }
        if (state.compareAndSet(CaptureState.COPYING, CaptureState.PROCESSING)) {
            startProcessing(bitmap)
        } else {
            bitmap.recycleSafely()
            completeWithoutProcessing()
        }
    }

    private fun claimFrame(image: ProjectionFrame): FrameClaim = synchronized(setupOwnershipLock) {
        when (state.get()) {
            CaptureState.SETTING_UP,
            CaptureState.SETUP_STOP_PENDING,
            -> if (pendingSetupFrame.compareAndSet(null, image)) {
                FrameClaim.STASHED
            } else {
                FrameClaim.CLOSE
            }
            CaptureState.WAITING ->
                if (state.compareAndSet(CaptureState.WAITING, CaptureState.COPYING)) {
                    FrameClaim.CLAIMED
                } else {
                    FrameClaim.CLOSE
                }
            else -> FrameClaim.CLOSE
        }
    }

    private fun startProcessing(bitmap: Bitmap) {
        val bitmapReleased = AtomicBoolean(false)
        fun releaseBitmap() {
            if (bitmapReleased.compareAndSet(false, true)) bitmap.recycleSafely()
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                process(bitmap)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Unit
            } finally {
                releaseBitmap()
            }
        }
        processingJob.set(job)
        job.invokeOnCompletion {
            releaseBitmap()
            completeProcessing()
        }
        when (state.get()) {
            CaptureState.PROCESSING -> job.start()
            CaptureState.STOP_DURING_PROCESSING -> job.cancel()
            else -> job.cancel()
        }
    }

    private fun completeWithoutProcessing() {
        while (true) {
            when (val current = state.get()) {
                CaptureState.COPYING,
                CaptureState.STOP_DURING_COPY,
                -> if (state.compareAndSet(current, CaptureState.FINISHED)) {
                    notifyFinished()
                    return
                }
                CaptureState.FINISHED -> return
                else -> return
            }
        }
    }

    private fun completeProcessing() {
        processingJob.set(null)
        while (true) {
            when (val current = state.get()) {
                CaptureState.PROCESSING,
                CaptureState.STOP_DURING_PROCESSING,
                -> if (state.compareAndSet(current, CaptureState.FINISHED)) {
                    notifyFinished()
                    return
                }
                CaptureState.FINISHED -> return
                else -> return
            }
        }
    }

    private fun cancelWaitingTimeout() {
        timeout.getAndSet(null)?.cancelSafely()
    }

    private fun armWaitingTimeout() {
        val scheduled = timeoutScheduler.schedule(timeoutMillis, ::onWaitingTimeout)
        timeout.set(scheduled)
        if (state.get() != CaptureState.WAITING) cancelWaitingTimeout()
    }

    private fun finishSetupWithoutWaiting(): Boolean {
        val pendingFrame = synchronized(setupOwnershipLock) {
            state.set(CaptureState.FINISHED)
            pendingSetupFrame.getAndSet(null)
        }
        pendingFrame?.closeSafely()
        cancelWaitingTimeout()
        closeCaptureResources()
        notifyFinished()
        return false
    }

    private fun requestSetupStop(): Boolean = synchronized(setupOwnershipLock) {
        when (state.get()) {
            CaptureState.SETTING_UP -> {
                state.set(CaptureState.SETUP_STOP_PENDING)
                true
            }
            CaptureState.SETUP_STOP_PENDING,
            CaptureState.FINISHED,
            -> true
            else -> false
        }
    }

    private fun setupIsInProgress(): Boolean = when (state.get()) {
        CaptureState.SETTING_UP,
        CaptureState.SETUP_STOP_PENDING,
        -> true
        else -> false
    }

    private fun setupStopIsPending(): Boolean = state.get() == CaptureState.SETUP_STOP_PENDING

    private fun closeCaptureResources() {
        val opened = resources.getAndSet(null) ?: return
        if (!resourcesClosed.compareAndSet(false, true)) return
        runSafely(opened::closeImageReader)
        runSafely(opened::releaseVirtualDisplay)
        runSafely(opened::unregisterProjectionCallback)
        runSafely(opened::stopProjection)
        runSafely(opened::quitHandlerThread)
    }

    private fun notifyFinished() {
        if (finishedCallbackSent.compareAndSet(false, true)) runSafely(onFinished)
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

    private enum class CaptureState {
        NEW,
        SETTING_UP,
        SETUP_STOP_PENDING,
        WAITING,
        COPYING,
        STOP_DURING_COPY,
        PROCESSING,
        STOP_DURING_PROCESSING,
        FINISHED,
    }

    private enum class FrameClaim {
        STASHED,
        CLAIMED,
        CLOSE,
    }
}
