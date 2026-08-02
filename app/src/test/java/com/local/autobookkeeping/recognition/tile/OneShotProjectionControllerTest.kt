package com.local.autobookkeeping.recognition.tile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class OneShotProjectionControllerTest {
    @Test
    fun `one frame uses one projection display and closes every resource`() = runTest {
        val resources = FakeProjectionResources()
        val scheduler = FakeTimeoutScheduler()
        val processed = mutableListOf<Bitmap>()
        val controller = controller(
            resources = resources,
            scheduler = scheduler,
            process = { bitmap ->
                processed += bitmap
                bitmap.recycle() // ScreenshotRecognitionController owns the bitmap too.
            },
        )
        val frame = FakeProjectionFrame(bitmap())

        assertTrue(controller.start(grant()))
        resources.emit(frame)
        advanceUntilIdle()

        assertEquals(1, resources.factoryCalls)
        assertEquals(1, resources.virtualDisplayCreates)
        assertEquals(listOf("projection-callback", "image-listener", "virtual-display"), resources.setupEvents)
        assertEquals(1, processed.size)
        assertSame(frame.bitmap, processed.single())
        assertTrue(frame.closed)
        assertTrue(frame.bitmap.isRecycled)
        assertTrue(resources.virtualDisplayReleased)
        assertTrue(resources.imageReaderClosed)
        assertTrue(resources.projectionCallbackUnregistered)
        assertTrue(resources.projectionStopped)
        assertTrue(resources.handlerThreadQuit)
        assertTrue(scheduler.timeout.cancelled)
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `second image from same token is atomically ignored and closed`() = runTest {
        val resources = FakeProjectionResources()
        val calls = AtomicInteger(0)
        val controller = controller(resources = resources) { calls.incrementAndGet() }
        val first = FakeProjectionFrame(bitmap())
        val second = FakeProjectionFrame(bitmap())

        controller.start(grant())
        resources.emit(first)
        resources.emit(second)
        advanceUntilIdle()

        assertEquals(1, calls.get())
        assertTrue(first.closed)
        assertTrue(second.closed)
        assertTrue(first.bitmap.isRecycled)
        assertFalse(second.bitmap.isRecycled)
        second.bitmap.recycle()
        assertEquals(1, resources.virtualDisplayCreates)
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `timeout stop and error each clean all resources without processing`() = runTest {
        val triggers = listOf<(FakeProjectionResources, FakeTimeoutScheduler) -> Unit>(
            { _, scheduler -> scheduler.fire() },
            { resources, _ -> resources.emitProjectionStop() },
            { resources, _ -> resources.emitError() },
        )

        triggers.forEach { trigger ->
            val resources = FakeProjectionResources()
            val scheduler = FakeTimeoutScheduler()
            var processCalls = 0
            val controller = controller(resources, scheduler) { processCalls += 1 }

            controller.start(grant())
            trigger(resources, scheduler)
            advanceUntilIdle()

            assertEquals(0, processCalls)
            resources.assertAllSessionResourcesClosed()
            assertEquals(1, resources.finishedCalls)
        }
    }

    @Test
    fun `timeout during suspended processing cancels work and releases frame bitmap`() = runTest {
        val resources = FakeProjectionResources()
        val scheduler = FakeTimeoutScheduler()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = controller(resources, scheduler) {
            entered.complete(Unit)
            release.await()
        }
        val frame = FakeProjectionFrame(bitmap())

        controller.start(grant())
        resources.emit(frame)
        advanceUntilIdle()
        entered.await()
        scheduler.fire()
        advanceUntilIdle()
        val frameClosedAtTimeout = frame.closed
        val bitmapRecycledAtTimeout = frame.bitmap.isRecycled
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(frameClosedAtTimeout)
        assertTrue(bitmapRecycledAtTimeout)
        resources.assertAllSessionResourcesClosed()
    }

    @Test
    fun `copy and processor failures close frame bitmap and every session resource`() = runTest {
        val copyFailureResources = FakeProjectionResources()
        val copyFailureController = controller(copyFailureResources) {
            error("processor must not run")
        }
        val copyFailureFrame = FakeProjectionFrame(bitmap(), copyFailure = true)
        copyFailureController.start(grant())
        copyFailureResources.emit(copyFailureFrame)
        advanceUntilIdle()

        assertTrue(copyFailureFrame.closed)
        copyFailureFrame.bitmap.recycle()
        copyFailureResources.assertAllSessionResourcesClosed()

        val processorFailureResources = FakeProjectionResources()
        val processorFailureController = controller(processorFailureResources) {
            error("private OCR contents")
        }
        val processorFailureFrame = FakeProjectionFrame(bitmap())
        processorFailureController.start(grant())
        processorFailureResources.emit(processorFailureFrame)
        advanceUntilIdle()

        assertTrue(processorFailureFrame.closed)
        assertTrue(processorFailureFrame.bitmap.isRecycled)
        processorFailureResources.assertAllSessionResourcesClosed()
    }

    @Test
    fun `cleanup continues when individual resource close operations throw`() = runTest {
        val resources = FakeProjectionResources(throwDuringCleanup = true)
        val controller = controller(resources) { }
        val frame = FakeProjectionFrame(bitmap(), throwOnClose = true)

        controller.start(grant())
        resources.emit(frame)
        advanceUntilIdle()

        assertTrue(frame.closeAttempted)
        assertTrue(frame.bitmap.isRecycled)
        resources.assertAllSessionCleanupAttempted()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `setup failure cleans resources and a grant cannot be started twice`() = runTest {
        val resources = FakeProjectionResources(failCreatingDisplay = true)
        val controller = controller(resources) { }

        assertFalse(controller.start(grant()))
        assertFalse(controller.start(grant()))

        assertEquals(1, resources.factoryCalls)
        assertEquals(1, resources.virtualDisplayCreates)
        resources.assertAllSessionResourcesClosed()
        assertEquals(1, resources.finishedCalls)
    }

    private fun CoroutineScope.controller(
        resources: FakeProjectionResources,
        scheduler: FakeTimeoutScheduler = FakeTimeoutScheduler(),
        process: suspend (Bitmap) -> Unit,
    ) = OneShotProjectionController(
        resourceFactory = ProjectionResourceFactory {
            resources.factoryCalls += 1
            resources
        },
        process = process,
        scope = this,
        timeoutScheduler = scheduler,
        timeoutMillis = 1_000L,
        onFinished = { resources.finishedCalls += 1 },
    )

    private class FakeProjectionResources(
        private val failCreatingDisplay: Boolean = false,
        private val throwDuringCleanup: Boolean = false,
    ) : ProjectionCaptureResources {
        var factoryCalls = 0
        var virtualDisplayCreates = 0
        var finishedCalls = 0
        val setupEvents = mutableListOf<String>()
        var virtualDisplayReleased = false
        var imageReaderClosed = false
        var projectionCallbackUnregistered = false
        var projectionStopped = false
        var handlerThreadQuit = false
        private val frames = ArrayDeque<ProjectionFrame>()
        private var imageListener: (() -> Unit)? = null
        private var stopListener: (() -> Unit)? = null
        private var errorListener: (() -> Unit)? = null

        override fun registerProjectionCallback(onStop: () -> Unit) {
            setupEvents += "projection-callback"
            stopListener = onStop
        }

        override fun setImageListener(onImage: () -> Unit, onError: () -> Unit) {
            setupEvents += "image-listener"
            imageListener = onImage
            errorListener = onError
        }

        override fun createVirtualDisplay() {
            setupEvents += "virtual-display"
            virtualDisplayCreates += 1
            if (failCreatingDisplay) error("display setup failed")
        }

        override fun acquireLatestImage(): ProjectionFrame? = frames.removeFirstOrNull()

        override fun releaseVirtualDisplay() {
            virtualDisplayReleased = true
            if (throwDuringCleanup) error("release failed")
        }

        override fun closeImageReader() {
            imageReaderClosed = true
            if (throwDuringCleanup) error("reader close failed")
        }

        override fun unregisterProjectionCallback() {
            projectionCallbackUnregistered = true
            if (throwDuringCleanup) error("callback unregister failed")
        }

        override fun stopProjection() {
            projectionStopped = true
            if (throwDuringCleanup) error("projection stop failed")
        }

        override fun quitHandlerThread() {
            handlerThreadQuit = true
            if (throwDuringCleanup) error("thread quit failed")
        }

        fun emit(frame: ProjectionFrame) {
            frames += frame
            imageListener?.invoke()
        }

        fun emitProjectionStop() = stopListener?.invoke() ?: Unit
        fun emitError() = errorListener?.invoke() ?: Unit

        fun assertAllSessionResourcesClosed() {
            assertTrue(virtualDisplayReleased)
            assertTrue(imageReaderClosed)
            assertTrue(projectionCallbackUnregistered)
            assertTrue(projectionStopped)
            assertTrue(handlerThreadQuit)
        }

        fun assertAllSessionCleanupAttempted() = assertAllSessionResourcesClosed()
    }

    private class FakeProjectionFrame(
        val bitmap: Bitmap,
        private val copyFailure: Boolean = false,
        private val throwOnClose: Boolean = false,
    ) : ProjectionFrame {
        var closeAttempted = false
        var closed = false

        override fun copyToSoftwareBitmap(): Bitmap {
            if (copyFailure) error("invalid plane")
            return bitmap
        }

        override fun close() {
            closeAttempted = true
            closed = true
            if (throwOnClose) error("image close failed")
        }
    }

    private class FakeTimeoutScheduler : ProjectionTimeoutScheduler {
        val timeout = FakeTimeout()
        private var action: (() -> Unit)? = null

        override fun schedule(delayMillis: Long, action: () -> Unit): ProjectionTimeout {
            assertEquals(1_000L, delayMillis)
            this.action = action
            return timeout
        }

        fun fire() = action?.invoke() ?: Unit
    }

    private class FakeTimeout : ProjectionTimeout {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private companion object {
        fun bitmap(): Bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
        fun grant() = ProjectionGrant(Activity.RESULT_OK, Intent("one-shot-grant"))
    }
}
