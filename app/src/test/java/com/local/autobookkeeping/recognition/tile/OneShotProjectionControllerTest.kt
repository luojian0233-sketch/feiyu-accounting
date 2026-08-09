package com.local.autobookkeeping.recognition.tile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    fun `stale timeout during blocking copy cannot steal claimed frame`() = runTest {
        val resources = FakeProjectionResources()
        val scheduler = FakeTimeoutScheduler()
        var processCalls = 0
        val controller = controller(resources, scheduler) { processCalls += 1 }
        val frame = BlockingProjectionFrame(bitmap())

        controller.start(grant())
        val emitter = thread(name = "timeout-blocking-copy") { resources.emit(frame) }
        assertTrue(frame.copyStarted.await(5, TimeUnit.SECONDS))
        scheduler.fire()
        assertFalse(frame.closed)
        assertFalse(resources.imageReaderClosed)
        assertFalse(resources.virtualDisplayReleased)
        assertFalse(resources.projectionStopped)

        frame.releaseCopy.countDown()
        emitter.join(5_000L)
        advanceUntilIdle()

        assertFalse(emitter.isAlive)
        assertEquals(1, processCalls)
        assertTrue(frame.closed)
        assertTrue(frame.bitmap.isRecycled)
        resources.assertAllSessionResourcesClosed()
    }

    @Test
    fun `projection stop during blocking copy waits then cleans without processing`() = runTest {
        val resources = FakeProjectionResources()
        var processCalls = 0
        val controller = controller(resources) { processCalls += 1 }
        val frame = BlockingProjectionFrame(bitmap())

        controller.start(grant())
        val emitter = thread(name = "stop-blocking-copy") { resources.emit(frame) }
        assertTrue(frame.copyStarted.await(5, TimeUnit.SECONDS))
        resources.emitProjectionStop()
        assertFalse(frame.closed)
        assertFalse(resources.imageReaderClosed)
        assertFalse(resources.handlerThreadQuit)

        frame.releaseCopy.countDown()
        emitter.join(5_000L)
        advanceUntilIdle()

        assertFalse(emitter.isAlive)
        assertEquals(0, processCalls)
        assertTrue(frame.closed)
        assertTrue(frame.bitmap.isRecycled)
        resources.assertAllSessionResourcesClosed()
    }

    @Test
    fun `waiting timeout wins before frame and late frame is only closed`() = runTest {
        val resources = FakeProjectionResources()
        val scheduler = FakeTimeoutScheduler()
        var processCalls = 0
        val controller = controller(resources, scheduler) { processCalls += 1 }
        val lateFrame = FakeProjectionFrame(bitmap())

        controller.start(grant())
        scheduler.fire()
        resources.emit(lateFrame)
        advanceUntilIdle()

        assertEquals(0, processCalls)
        assertTrue(lateFrame.closed)
        assertFalse(lateFrame.bitmap.isRecycled)
        lateFrame.bitmap.recycle()
        resources.assertAllSessionResourcesClosed()
    }

    @Test
    fun `first frame wins and cancels waiting timeout before processing`() = runTest {
        val resources = FakeProjectionResources()
        val scheduler = FakeTimeoutScheduler()
        val controller = controller(resources, scheduler) { }

        controller.start(grant())
        resources.emit(FakeProjectionFrame(bitmap()))

        assertTrue(scheduler.timeout.cancelled)
        resources.assertAllSessionResourcesClosed()
        advanceUntilIdle()
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

    @Test
    fun `synchronous projection stop during callback registration is idempotent`() = runTest {
        val resources = FakeProjectionResources(stopDuringRegistration = true)
        val controller = controller(resources) { error("must not process") }

        assertFalse(controller.start(grant()))

        assertEquals(listOf("projection-callback"), resources.setupEvents)
        assertEquals(0, resources.virtualDisplayCreates)
        resources.assertAllSessionResourcesClosed()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `stop while resource factory is opening closes resources returned later`() = runTest {
        val resources = FakeProjectionResources()
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val controller = OneShotProjectionController(
            resourceFactory = ProjectionResourceFactory {
                factoryEntered.countDown()
                check(releaseFactory.await(5, TimeUnit.SECONDS))
                resources
            },
            process = { error("must not process") },
            scope = this,
            timeoutScheduler = FakeTimeoutScheduler(),
            timeoutMillis = 1_000L,
            onFinished = { resources.finishedCalls += 1 },
        )
        var startResult = true

        val starter = thread(name = "blocking-projection-factory") {
            startResult = controller.start(grant())
        }
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
        controller.stop()
        releaseFactory.countDown()
        starter.join(5_000L)

        assertFalse(starter.isAlive)
        assertFalse(startResult)
        resources.assertAllSessionResourcesClosed()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `stop before start permanently prevents resource factory open`() = runTest {
        val resources = FakeProjectionResources()
        val controller = controller(resources) { error("must not process") }

        controller.stop()

        assertFalse(controller.start(grant()))
        assertEquals(0, resources.factoryCalls)
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `async stop while callback registration is blocked waits for publication then cleans once`() = runTest {
        val resources = FakeProjectionResources(blockRegistration = true)
        val controller = controller(resources) { error("must not process") }
        var startResult = true

        val starter = thread(name = "blocked-callback-registration") {
            startResult = controller.start(grant())
        }
        assertTrue(resources.registrationEntered.await(5, TimeUnit.SECONDS))
        resources.emitProjectionStop()
        assertFalse(resources.imageReaderClosed)
        assertFalse(resources.projectionStopped)

        resources.releaseRegistration.countDown()
        starter.join(5_000L)

        assertFalse(starter.isAlive)
        assertFalse(startResult)
        assertFalse(resources.callbackRegistered)
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(0, resources.virtualDisplayCreates)
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `async stop while virtual display creation is blocked releases published display once`() = runTest {
        val resources = FakeProjectionResources(blockDisplayCreation = true)
        val controller = controller(resources) { error("must not process") }
        var startResult = true

        val starter = thread(name = "blocked-virtual-display") {
            startResult = controller.start(grant())
        }
        assertTrue(resources.displayCreationEntered.await(5, TimeUnit.SECONDS))
        resources.emitProjectionStop()
        assertFalse(resources.imageReaderClosed)
        assertFalse(resources.projectionStopped)

        resources.releaseDisplayCreation.countDown()
        starter.join(5_000L)

        assertFalse(starter.isAlive)
        assertFalse(startResult)
        assertFalse(resources.virtualDisplayPresent)
        assertFalse(resources.callbackRegistered)
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(1, resources.virtualDisplayCreates)
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `frame emitted before virtual display publication waits without copying or cleanup`() = runTest {
        val resources = FakeProjectionResources(blockDisplayCreation = true)
        val processCalls = AtomicInteger(0)
        val controller = controller(resources) { processCalls.incrementAndGet() }
        val frame = FakeProjectionFrame(bitmap())

        val starter = thread(name = "frame-before-virtual-display-publication") {
            controller.start(grant())
        }
        assertTrue(resources.displayCreationEntered.await(5, TimeUnit.SECONDS))
        resources.emit(frame)
        val copyCallsBeforePublication = frame.copyCalls
        val processCallsBeforePublication = processCalls.get()
        val frameClosedBeforePublication = frame.closed
        val readerClosedBeforePublication = resources.imageReaderClosed
        val projectionStoppedBeforePublication = resources.projectionStopped

        resources.releaseDisplayCreation.countDown()
        starter.join(5_000L)
        advanceUntilIdle()

        assertFalse(starter.isAlive)
        assertEquals(0, copyCallsBeforePublication)
        assertEquals(0, processCallsBeforePublication)
        assertFalse(frameClosedBeforePublication)
        assertFalse(readerClosedBeforePublication)
        assertFalse(projectionStoppedBeforePublication)
        assertEquals(1, frame.copyCalls)
        assertEquals(1, processCalls.get())
        assertTrue(frame.closed)
        assertTrue(frame.bitmap.isRecycled)
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `pending setup frame and stop close both frames without copying after display publication`() = runTest {
        val resources = FakeProjectionResources(blockDisplayCreation = true)
        val processCalls = AtomicInteger(0)
        val controller = controller(resources) { processCalls.incrementAndGet() }
        val first = FakeProjectionFrame(bitmap())
        val second = FakeProjectionFrame(bitmap())

        val starter = thread(name = "pending-frame-stop-before-display-publication") {
            controller.start(grant())
        }
        assertTrue(resources.displayCreationEntered.await(5, TimeUnit.SECONDS))
        resources.emit(first)
        resources.emit(second)
        controller.stop()
        val firstClosedBeforePublication = first.closed
        val secondClosedBeforePublication = second.closed
        val readerClosedBeforePublication = resources.imageReaderClosed

        resources.releaseDisplayCreation.countDown()
        starter.join(5_000L)
        advanceUntilIdle()

        assertFalse(starter.isAlive)
        assertFalse(firstClosedBeforePublication)
        assertTrue(secondClosedBeforePublication)
        assertFalse(readerClosedBeforePublication)
        assertEquals(0, first.copyCalls)
        assertEquals(0, second.copyCalls)
        assertEquals(0, processCalls.get())
        assertTrue(first.closed)
        assertTrue(second.closed)
        first.bitmap.recycle()
        second.bitmap.recycle()
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `pending setup frame is closed without copying when display creation fails`() = runTest {
        val resources = FakeProjectionResources(
            blockDisplayCreation = true,
            failDisplayAfterBlock = true,
        )
        val processCalls = AtomicInteger(0)
        val controller = controller(resources) { processCalls.incrementAndGet() }
        val frame = FakeProjectionFrame(bitmap())
        var startResult = true

        val starter = thread(name = "pending-frame-display-failure") {
            startResult = controller.start(grant())
        }
        assertTrue(resources.displayCreationEntered.await(5, TimeUnit.SECONDS))
        resources.emit(frame)
        assertEquals(0, frame.copyCalls)
        assertFalse(frame.closed)
        assertFalse(resources.imageReaderClosed)

        resources.releaseDisplayCreation.countDown()
        starter.join(5_000L)
        advanceUntilIdle()

        assertFalse(starter.isAlive)
        assertFalse(startResult)
        assertEquals(0, frame.copyCalls)
        assertEquals(0, processCalls.get())
        assertTrue(frame.closed)
        frame.bitmap.recycle()
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `frame arriving while failed setup closes detached pending frame is also closed`() = runTest {
        val resources = FakeProjectionResources(
            blockDisplayCreation = true,
            failDisplayAfterBlock = true,
        )
        val processCalls = AtomicInteger(0)
        val controller = controller(resources) { processCalls.incrementAndGet() }
        val first = BlockingCloseProjectionFrame(bitmap())
        val second = FakeProjectionFrame(bitmap())
        var startResult = true

        val starter = thread(name = "frame-during-failed-setup-cleanup") {
            startResult = controller.start(grant())
        }
        assertTrue(resources.displayCreationEntered.await(5, TimeUnit.SECONDS))
        resources.emit(first)
        resources.releaseDisplayCreation.countDown()
        assertTrue(first.closeStarted.await(5, TimeUnit.SECONDS))

        resources.emit(second)
        first.releaseClose.countDown()
        starter.join(5_000L)
        advanceUntilIdle()

        assertFalse(starter.isAlive)
        assertFalse(startResult)
        assertEquals(0, first.copyCalls)
        assertEquals(0, second.copyCalls)
        assertEquals(0, processCalls.get())
        assertTrue(first.closed)
        assertTrue(second.closed)
        first.bitmap.recycle()
        second.bitmap.recycle()
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `pending setup frame owns handoff before waiting timeout or later frame`() = runTest {
        val resources = FakeProjectionResources(blockDisplayCreation = true)
        val handoffStarted = CountDownLatch(1)
        val first = BlockingProjectionFrame(bitmap(), copyStartedSignal = handoffStarted)
        val second = FakeProjectionFrame(bitmap())
        val processed = mutableListOf<Bitmap>()
        val scheduler = FakeTimeoutScheduler(
            onSchedule = {
                resources.emit(second)
                handoffStarted.countDown()
            },
        )
        val controller = controller(resources, scheduler) { processed += it }

        val starter = thread(name = "pending-frame-owns-setup-handoff") {
            controller.start(grant())
        }
        assertTrue(resources.displayCreationEntered.await(5, TimeUnit.SECONDS))
        resources.emit(first)
        resources.releaseDisplayCreation.countDown()
        assertTrue(handoffStarted.await(5, TimeUnit.SECONDS))
        if (scheduler.scheduleCalls == 0) resources.emit(second)
        first.releaseCopy.countDown()
        starter.join(5_000L)
        advanceUntilIdle()

        assertFalse(starter.isAlive)
        assertEquals(0, scheduler.scheduleCalls)
        assertEquals(1, processed.size)
        assertSame(first.bitmap, processed.single())
        assertEquals(1, first.copyCalls)
        assertEquals(0, second.copyCalls)
        assertTrue(first.closed)
        assertTrue(second.closed)
        assertTrue(first.bitmap.isRecycled)
        second.bitmap.recycle()
        resources.assertAllSessionResourcesClosedExactlyOnce()
        assertEquals(1, resources.finishedCalls)
    }

    @Test
    fun `setup exception after pending async stop still closes every resource once`() = runTest {
        val resources = FakeProjectionResources(
            blockRegistration = true,
            failRegistrationAfterBlock = true,
        )
        val controller = controller(resources) { error("must not process") }
        var startResult = true

        val starter = thread(name = "failing-callback-registration") {
            startResult = controller.start(grant())
        }
        assertTrue(resources.registrationEntered.await(5, TimeUnit.SECONDS))
        resources.emitProjectionStop()
        assertFalse(resources.imageReaderClosed)
        assertFalse(resources.projectionStopped)
        resources.releaseRegistration.countDown()
        starter.join(5_000L)

        assertFalse(starter.isAlive)
        assertFalse(startResult)
        assertFalse(resources.callbackRegistered)
        resources.assertAllSessionResourcesClosedExactlyOnce()
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
        private val stopDuringRegistration: Boolean = false,
        private val blockRegistration: Boolean = false,
        private val failRegistrationAfterBlock: Boolean = false,
        private val blockDisplayCreation: Boolean = false,
        private val failDisplayAfterBlock: Boolean = false,
    ) : ProjectionCaptureResources {
        var factoryCalls = 0
        var virtualDisplayCreates = 0
        var finishedCalls = 0
        val setupEvents = mutableListOf<String>()
        @Volatile var virtualDisplayReleased = false
        @Volatile var imageReaderClosed = false
        @Volatile var projectionCallbackUnregistered = false
        @Volatile var projectionStopped = false
        @Volatile var handlerThreadQuit = false
        @Volatile var virtualDisplayPresent = false
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val displayCreationEntered = CountDownLatch(1)
        val releaseDisplayCreation = CountDownLatch(1)
        var virtualDisplayReleaseCalls = 0
        var imageReaderCloseCalls = 0
        var projectionCallbackUnregisterCalls = 0
        var projectionStopCalls = 0
        var handlerThreadQuitCalls = 0
        private val frames = ArrayDeque<ProjectionFrame>()
        private var imageListener: (() -> Unit)? = null
        @Volatile private var stopListener: (() -> Unit)? = null
        @Volatile private var inFlightStopListener: (() -> Unit)? = null
        private var errorListener: (() -> Unit)? = null
        val callbackRegistered: Boolean get() = stopListener != null

        override fun registerProjectionCallback(onStop: () -> Unit) {
            setupEvents += "projection-callback"
            inFlightStopListener = onStop
            if (stopDuringRegistration) onStop()
            if (blockRegistration) {
                registrationEntered.countDown()
                check(releaseRegistration.await(5, TimeUnit.SECONDS))
            }
            if (failRegistrationAfterBlock) error("registration failed")
            stopListener = onStop
            inFlightStopListener = null
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
            if (blockDisplayCreation) {
                displayCreationEntered.countDown()
                check(releaseDisplayCreation.await(5, TimeUnit.SECONDS))
            }
            if (failDisplayAfterBlock) error("display setup failed after block")
            virtualDisplayPresent = true
        }

        override fun acquireLatestImage(): ProjectionFrame? = frames.removeFirstOrNull()

        override fun releaseVirtualDisplay() {
            virtualDisplayReleaseCalls += 1
            virtualDisplayReleased = true
            virtualDisplayPresent = false
            if (throwDuringCleanup) error("release failed")
        }

        override fun closeImageReader() {
            imageReaderCloseCalls += 1
            imageReaderClosed = true
            if (throwDuringCleanup) error("reader close failed")
        }

        override fun unregisterProjectionCallback() {
            projectionCallbackUnregisterCalls += 1
            projectionCallbackUnregistered = true
            stopListener = null
            if (throwDuringCleanup) error("callback unregister failed")
        }

        override fun stopProjection() {
            projectionStopCalls += 1
            projectionStopped = true
            if (throwDuringCleanup) error("projection stop failed")
        }

        override fun quitHandlerThread() {
            handlerThreadQuitCalls += 1
            handlerThreadQuit = true
            if (throwDuringCleanup) error("thread quit failed")
        }

        fun emit(frame: ProjectionFrame) {
            frames += frame
            imageListener?.invoke()
        }

        fun emitProjectionStop() = (stopListener ?: inFlightStopListener)?.invoke() ?: Unit
        fun emitError() = errorListener?.invoke() ?: Unit

        fun assertAllSessionResourcesClosed() {
            assertTrue(virtualDisplayReleased)
            assertTrue(imageReaderClosed)
            assertTrue(projectionCallbackUnregistered)
            assertTrue(projectionStopped)
            assertTrue(handlerThreadQuit)
        }

        fun assertAllSessionCleanupAttempted() = assertAllSessionResourcesClosed()

        fun assertAllSessionResourcesClosedExactlyOnce() {
            assertAllSessionResourcesClosed()
            assertEquals(1, imageReaderCloseCalls)
            assertEquals(1, virtualDisplayReleaseCalls)
            assertEquals(1, projectionCallbackUnregisterCalls)
            assertEquals(1, projectionStopCalls)
            assertEquals(1, handlerThreadQuitCalls)
        }
    }

    private class FakeProjectionFrame(
        val bitmap: Bitmap,
        private val copyFailure: Boolean = false,
        private val throwOnClose: Boolean = false,
    ) : ProjectionFrame {
        var closeAttempted = false
        var closed = false
        @Volatile var copyCalls = 0

        override fun copyToSoftwareBitmap(): Bitmap {
            copyCalls += 1
            if (copyFailure) error("invalid plane")
            return bitmap
        }

        override fun close() {
            closeAttempted = true
            closed = true
            if (throwOnClose) error("image close failed")
        }
    }

    private class BlockingProjectionFrame(
        val bitmap: Bitmap,
        private val copyStartedSignal: CountDownLatch? = null,
    ) : ProjectionFrame {
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var copyCalls = 0

        override fun copyToSoftwareBitmap(): Bitmap {
            copyCalls += 1
            copyStarted.countDown()
            copyStartedSignal?.countDown()
            check(releaseCopy.await(5, TimeUnit.SECONDS))
            return bitmap
        }

        override fun close() {
            closed = true
        }
    }

    private class BlockingCloseProjectionFrame(
        val bitmap: Bitmap,
    ) : ProjectionFrame {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var copyCalls = 0

        override fun copyToSoftwareBitmap(): Bitmap {
            copyCalls += 1
            return bitmap
        }

        override fun close() {
            closeStarted.countDown()
            check(releaseClose.await(5, TimeUnit.SECONDS))
            closed = true
        }
    }

    private class FakeTimeoutScheduler(
        private val onSchedule: () -> Unit = {},
    ) : ProjectionTimeoutScheduler {
        val timeout = FakeTimeout()
        private var action: (() -> Unit)? = null
        @Volatile var scheduleCalls = 0

        override fun schedule(delayMillis: Long, action: () -> Unit): ProjectionTimeout {
            assertEquals(1_000L, delayMillis)
            scheduleCalls += 1
            this.action = action
            onSchedule()
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
