package com.local.autobookkeeping

import androidx.lifecycle.SavedStateHandle
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainLaunchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val codec = ReviewRequestCodec()

    @BeforeEach
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `delayed pending load survives repeated processing and is acknowledged once`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<Transaction?>()
            var findCalls = 0
            val viewModel = viewModel(
                findById = {
                    findCalls += 1
                    release.await()
                },
            )
            val token = viewModel.accept(MainLaunchRequest.Pending(73))

            viewModel.loadPending(token)
            runCurrent()
            viewModel.loadPending(token)
            runCurrent()

            assertEquals(1, findCalls)
            assertEquals(
                MainLaunchUiState.Pending(token, transactionId = 73, processing = true),
                viewModel.state.value,
            )

            val transaction = transaction(73)
            release.complete(transaction)
            advanceUntilIdle()

            assertEquals(MainLaunchUiState.OpenPending(token, transaction), viewModel.state.value)
            assertTrue(viewModel.acknowledge(token))
            assertEquals(MainLaunchUiState.Idle, viewModel.state.value)
            assertFalse(viewModel.acknowledge(token))
        }

    @Test
    fun `saved request restores after process recreation without persisting domain objects`() =
        runTest(dispatcher) {
            val savedState = SavedStateHandle()
            val original = viewModel(savedStateHandle = savedState)
            val request = ReviewRequest(listOf(choice(DIGEST_A, 73)))
            val token = original.accept(MainLaunchRequest.Review(request))

            val restoredHandle = SavedStateHandle(
                savedState.keys().associateWith { key -> savedState.get<Any?>(key) },
            )
            val restored = viewModel(savedStateHandle = restoredHandle)

            assertEquals(MainLaunchUiState.Review(token, request, processing = false), restored.state.value)
            assertTrue(restoredHandle.keys().none { key -> restoredHandle.get<Any?>(key) is Transaction })
            assertTrue(restoredHandle.keys().none { key -> restoredHandle.get<Any?>(key) is ReviewRequest })
        }

    @Test
    fun `late completion from request A cannot clear or navigate over request B`() =
        runTest(dispatcher) {
            val releaseA = CompletableDeferred<Unit>()
            val requestA = ReviewRequest(listOf(choice(DIGEST_A, 73)))
            val requestB = ReviewRequest(listOf(choice(DIGEST_B, 74)))
            val viewModel = viewModel(
                resolve = { selected ->
                    if (selected.candidate.evidenceDigest == DIGEST_A) {
                        withContext(NonCancellable) { releaseA.await() }
                        RecognitionOutcome.Saved(transactionId = 91, pending = false)
                    } else {
                        RecognitionOutcome.Saved(transactionId = 92, pending = false)
                    }
                },
            )
            val tokenA = viewModel.accept(MainLaunchRequest.Review(requestA))
            viewModel.selectReview(tokenA, requestA.options.single())
            runCurrent()

            val tokenB = viewModel.accept(MainLaunchRequest.Review(requestB))
            releaseA.complete(Unit)
            advanceUntilIdle()

            assertTrue(tokenB > tokenA)
            assertEquals(MainLaunchUiState.Review(tokenB, requestB, processing = false), viewModel.state.value)
        }

    @Test
    fun `new request cancels old resolve job without showing an error`() = runTest(dispatcher) {
        val cancelled = CompletableDeferred<Unit>()
        val requestA = ReviewRequest(listOf(choice(DIGEST_A, 73)))
        val requestB = ReviewRequest(listOf(choice(DIGEST_B, 74)))
        val viewModel = viewModel(
            resolve = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val tokenA = viewModel.accept(MainLaunchRequest.Review(requestA))
        viewModel.selectReview(tokenA, requestA.options.single())
        runCurrent()

        val tokenB = viewModel.accept(MainLaunchRequest.Review(requestB))
        runCurrent()

        assertTrue(cancelled.isCompleted)
        assertEquals(MainLaunchUiState.Review(tokenB, requestB, processing = false), viewModel.state.value)
    }

    private fun viewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        findById: suspend (Long) -> Transaction? = { transaction(it) },
        resolve: suspend (RecognitionChoice) -> RecognitionOutcome = {
            RecognitionOutcome.Saved(transactionId = 91, pending = false)
        },
    ) = MainLaunchViewModel(
        savedStateHandle = savedStateHandle,
        reviewCodec = codec,
        findById = findById,
        resolve = resolve,
    )

    private fun choice(digest: String, transactionId: Long) = RecognitionChoice(
        candidate = RecognitionCandidate(
            platform = PaymentPlatform.WECHAT,
            channel = CaptureChannel.SCREENSHOT,
            eventKind = TransactionEventKind.PAYMENT,
            amountMinor = 12_345,
            direction = Direction.EXPENSE,
            merchant = "中国电信",
            account = "招商银行",
            occurredAt = Instant.parse("2026-07-29T08:30:00Z"),
            confidence = 87,
            evidenceDigest = digest,
        ),
        matchTransactionId = transactionId,
    )

    private fun transaction(id: Long) = Transaction(
        id = id,
        amountMinor = 12_345,
        direction = Direction.EXPENSE,
        category = "待确认",
        occurredAt = Instant.parse("2026-07-29T08:30:00Z"),
        pendingConfirmation = true,
    )

    private companion object {
        val DIGEST_A = "ab".repeat(32)
        val DIGEST_B = "cd".repeat(32)
    }
}
