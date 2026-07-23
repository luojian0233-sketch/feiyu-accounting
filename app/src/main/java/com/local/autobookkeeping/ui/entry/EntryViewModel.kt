package com.local.autobookkeeping.ui.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EntryValidationException(message: String) : IllegalArgumentException(message)

data class EntryDraft(
    val id: Long? = null,
    val amountText: String = "",
    val direction: Direction = Direction.EXPENSE,
    val category: String = "餐饮",
    val account: String = "",
    val occurredAt: Instant = Instant.now(),
    val merchant: String = "",
    val note: String = "",
    val internalTransfer: Boolean = false,
    val source: Source = Source.MANUAL,
    val pendingConfirmation: Boolean = false,
    val dedupeKey: String? = null,
    val refundOfId: Long? = null,
) {
    fun toTransaction(confirmPending: Boolean = false): Transaction {
        if (!AMOUNT_PATTERN.matches(amountText)) {
            throw EntryValidationException(AMOUNT_ERROR)
        }
        val amountMinor = try {
            BigDecimal(amountText).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw EntryValidationException(AMOUNT_ERROR)
        } catch (_: NumberFormatException) {
            throw EntryValidationException(AMOUNT_ERROR)
        }
        if (amountMinor <= 0) throw EntryValidationException(AMOUNT_ERROR)
        if (category.isBlank()) throw EntryValidationException("请选择分类")
        if (pendingConfirmation && confirmPending && (merchant.isBlank() || category.isBlank())) {
            throw EntryValidationException("确认待核对记录前，请填写商户并选择分类")
        }
        return Transaction(
            id = id ?: 0,
            amountMinor = amountMinor,
            direction = direction,
            category = category.trim(),
            occurredAt = occurredAt,
            merchant = merchant.trim(),
            note = note.trim(),
            account = account.trim(),
            source = source,
            pendingConfirmation = pendingConfirmation && !confirmPending,
            internalTransfer = internalTransfer,
            dedupeKey = dedupeKey,
            refundOfId = refundOfId,
        )
    }

    companion object {
        fun from(transaction: Transaction) = EntryDraft(
            id = transaction.id,
            amountText = formatAmountInput(transaction.amountMinor),
            direction = transaction.direction,
            category = transaction.category,
            account = transaction.account,
            occurredAt = transaction.occurredAt,
            merchant = transaction.merchant,
            note = transaction.note,
            internalTransfer = transaction.internalTransfer,
            source = transaction.source,
            pendingConfirmation = transaction.pendingConfirmation,
            dedupeKey = transaction.dedupeKey,
            refundOfId = transaction.refundOfId,
        )
    }
}

data class EntryUiState(
    val draft: EntryDraft = EntryDraft(),
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val error: String? = null,
)

class EntryViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(EntryUiState())
    val state: StateFlow<EntryUiState> = mutableState.asStateFlow()

    fun startNew() {
        mutableState.value = EntryUiState()
    }

    fun edit(transaction: Transaction) {
        mutableState.value = EntryUiState(draft = EntryDraft.from(transaction))
    }

    fun update(transform: (EntryDraft) -> EntryDraft) {
        mutableState.value = mutableState.value.copy(
            draft = transform(mutableState.value.draft),
            error = null,
        )
    }

    fun requestDelete() {
        if (mutableState.value.draft.id != null) {
            mutableState.value = mutableState.value.copy(showDeleteConfirmation = true)
        }
    }

    fun dismissDelete() {
        mutableState.value = mutableState.value.copy(showDeleteConfirmation = false)
    }

    fun save(confirmPending: Boolean = false, onSaved: (Long) -> Unit = {}) {
        val transaction = try {
            mutableState.value.draft.toTransaction(confirmPending)
        } catch (failure: EntryValidationException) {
            mutableState.value = mutableState.value.copy(error = failure.message)
            return
        }
        mutableState.value = mutableState.value.copy(saving = true, error = null)
        viewModelScope.launch {
            runCatching { repository.save(transaction) }
                .onSuccess { id ->
                    mutableState.value = mutableState.value.copy(saving = false)
                    onSaved(id)
                }
                .onFailure { failure ->
                    mutableState.value = mutableState.value.copy(
                        saving = false,
                        error = failure.message ?: "保存失败，请稍后重试",
                    )
                }
        }
    }

    fun confirmDelete(onDeleted: () -> Unit = {}) {
        val id = mutableState.value.draft.id ?: return
        mutableState.value = mutableState.value.copy(
            deleting = true,
            showDeleteConfirmation = false,
            error = null,
        )
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(deleting = false)
                    onDeleted()
                }
                .onFailure { failure ->
                    mutableState.value = mutableState.value.copy(
                        deleting = false,
                        error = failure.message ?: "删除失败，请稍后重试",
                    )
                }
        }
    }
}

private val AMOUNT_PATTERN = Regex("""(?:0|[1-9]\d*)(?:\.\d{1,2})?""")
private const val AMOUNT_ERROR = "请输入大于 0 且最多两位小数的金额"

internal fun formatAmountInput(amountMinor: Long): String =
    "${amountMinor / 100}.${(amountMinor % 100).toString().padStart(2, '0')}"
