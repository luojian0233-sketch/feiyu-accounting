package com.local.autobookkeeping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.domain.model.RecognitionChoice
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.recognition.ReviewRequest
import com.local.autobookkeeping.recognition.ReviewRequestCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface MainLaunchUiState {
    data object Idle : MainLaunchUiState

    data class Pending(
        val token: Long,
        val transactionId: Long,
        val processing: Boolean,
    ) : MainLaunchUiState

    data class Review(
        val token: Long,
        val request: ReviewRequest,
        val processing: Boolean,
    ) : MainLaunchUiState

    data class OpenPending(
        val token: Long,
        val transaction: Transaction,
    ) : MainLaunchUiState

    data class OpenLedger(val token: Long) : MainLaunchUiState

    data class Message(
        val token: Long,
        val text: String,
    ) : MainLaunchUiState
}

internal class MainLaunchViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val reviewCodec: ReviewRequestCodec,
    private val findById: suspend (Long) -> Transaction?,
    private val resolve: suspend (RecognitionChoice) -> com.local.autobookkeeping.domain.model.RecognitionOutcome,
) : ViewModel() {
    private val mutableState = MutableStateFlow(restoreState())
    val state: StateFlow<MainLaunchUiState> = mutableState.asStateFlow()

    private var activeJob: Job? = null

    fun accept(request: MainLaunchRequest): Long {
        activeJob?.cancel()
        activeJob = null
        val token = nextToken()
        mutableState.value = when (request) {
            is MainLaunchRequest.Pending -> {
                require(request.transactionId > 0)
                persist(KIND_PENDING, request.transactionId.toString(), token)
                MainLaunchUiState.Pending(token, request.transactionId, processing = false)
            }
            is MainLaunchRequest.Review -> {
                val encoded = reviewCodec.encode(request.request)
                persist(KIND_REVIEW, encoded, token)
                MainLaunchUiState.Review(token, request.request, processing = false)
            }
            MainLaunchRequest.Saved -> {
                persist(KIND_LEDGER, null, token)
                MainLaunchUiState.OpenLedger(token)
            }
        }
        return token
    }

    fun loadPending(token: Long) {
        val pending = mutableState.value as? MainLaunchUiState.Pending ?: return
        if (pending.token != token || pending.processing) return
        mutableState.value = pending.copy(processing = true)
        activeJob = viewModelScope.launch {
            val transaction = try {
                findById(pending.transactionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            if (mutableState.value.tokenOrNull() != token) return@launch
            mutableState.value = if (transaction == null) {
                persist(KIND_MESSAGE, PENDING_LOAD_FAILURE, token)
                MainLaunchUiState.Message(token, PENDING_LOAD_FAILURE)
            } else {
                MainLaunchUiState.OpenPending(token, transaction)
            }
        }
    }

    fun selectReview(token: Long, choice: RecognitionChoice) {
        val review = mutableState.value as? MainLaunchUiState.Review ?: return
        if (review.token != token || review.processing || choice !in review.request.options) return
        mutableState.value = review.copy(processing = true)
        activeJob = viewModelScope.launch {
            val resolution = try {
                resolve(choice).toReviewResolution()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                ReviewResolution.Message(REVIEW_FAILURE)
            }
            if (mutableState.value.tokenOrNull() != token) return@launch
            mutableState.value = resolution.toState(token)
        }
    }

    fun acknowledge(token: Long): Boolean {
        if (mutableState.value.tokenOrNull() != token) return false
        activeJob?.cancel()
        activeJob = null
        clearPersistedRequest()
        mutableState.value = MainLaunchUiState.Idle
        return true
    }

    private fun ReviewResolution.toState(token: Long): MainLaunchUiState = when (this) {
        is ReviewResolution.OpenPending -> {
            persist(KIND_PENDING, transactionId.toString(), token)
            MainLaunchUiState.Pending(token, transactionId, processing = false)
        }
        ReviewResolution.OpenLedger -> {
            persist(KIND_LEDGER, null, token)
            MainLaunchUiState.OpenLedger(token)
        }
        is ReviewResolution.SelectAgain -> try {
            val encoded = reviewCodec.encode(request)
            persist(KIND_REVIEW, encoded, token)
            MainLaunchUiState.Review(token, request, processing = false)
        } catch (_: IllegalArgumentException) {
            persist(KIND_MESSAGE, REVIEW_FAILURE, token)
            MainLaunchUiState.Message(token, REVIEW_FAILURE)
        }
        is ReviewResolution.Message -> {
            persist(KIND_MESSAGE, text, token)
            MainLaunchUiState.Message(token, text)
        }
    }

    private fun restoreState(): MainLaunchUiState {
        val token = savedStateHandle.get<Long>(KEY_ACTIVE_TOKEN) ?: return MainLaunchUiState.Idle
        val payload = savedStateHandle.get<String>(KEY_PAYLOAD)
        val restored = when (savedStateHandle.get<String>(KEY_KIND)) {
            KIND_PENDING -> payload
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { MainLaunchUiState.Pending(token, it, processing = false) }
            KIND_REVIEW -> payload
                ?.let(reviewCodec::decode)
                ?.let { MainLaunchUiState.Review(token, it, processing = false) }
            KIND_LEDGER -> MainLaunchUiState.OpenLedger(token)
            KIND_MESSAGE -> payload?.let { MainLaunchUiState.Message(token, it) }
            else -> null
        }
        if (restored == null) clearPersistedRequest()
        return restored ?: MainLaunchUiState.Idle
    }

    private fun nextToken(): Long {
        val previous = savedStateHandle.get<Long>(KEY_NEXT_TOKEN) ?: 0L
        val next = if (previous == Long.MAX_VALUE) 1L else previous + 1L
        savedStateHandle[KEY_NEXT_TOKEN] = next
        return next
    }

    private fun persist(kind: String, payload: String?, token: Long) {
        savedStateHandle[KEY_KIND] = kind
        savedStateHandle[KEY_PAYLOAD] = payload
        savedStateHandle[KEY_ACTIVE_TOKEN] = token
    }

    private fun clearPersistedRequest() {
        savedStateHandle.remove<String>(KEY_KIND)
        savedStateHandle.remove<String>(KEY_PAYLOAD)
        savedStateHandle.remove<Long>(KEY_ACTIVE_TOKEN)
    }

    private fun MainLaunchUiState.tokenOrNull(): Long? = when (this) {
        MainLaunchUiState.Idle -> null
        is MainLaunchUiState.Pending -> token
        is MainLaunchUiState.Review -> token
        is MainLaunchUiState.OpenPending -> token
        is MainLaunchUiState.OpenLedger -> token
        is MainLaunchUiState.Message -> token
    }

    private companion object {
        const val KEY_KIND = "recognition_launch_kind"
        const val KEY_PAYLOAD = "recognition_launch_payload"
        const val KEY_ACTIVE_TOKEN = "recognition_launch_active_token"
        const val KEY_NEXT_TOKEN = "recognition_launch_next_token"
        const val KIND_PENDING = "pending"
        const val KIND_REVIEW = "review"
        const val KIND_LEDGER = "ledger"
        const val KIND_MESSAGE = "message"
        const val PENDING_LOAD_FAILURE = "无法打开待核对记录"
        const val REVIEW_FAILURE = "无法完成识别"
    }
}
