package com.local.autobookkeeping.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.data.AccountSetting
import com.local.autobookkeeping.data.CategorySetting
import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.data.SettingsValidationException
import com.local.autobookkeeping.data.isValidBankPackage
import com.local.autobookkeeping.ui.theme.ThemeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex

fun interface NotificationAccessProvider {
    fun isEnabled(): Boolean
}

data class SettingsUiState(
    val theme: ThemeStyle = ThemeStyle.CLEAR_MINT,
    val wechatEnabled: Boolean = false,
    val alipayEnabled: Boolean = false,
    val bankPackageDraft: String = "",
    val categories: List<CategorySetting> = emptyList(),
    val accounts: List<AccountSetting> = emptyList(),
    val transactionCount: Int = 0,
    val notificationAccessEnabled: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val showFirstClearConfirmation: Boolean = false,
    val showFinalClearConfirmation: Boolean = false,
)

class SettingsViewModel(
    private val repository: SettingsOperations,
    private val notificationAccess: NotificationAccessProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SettingsUiState(notificationAccessEnabled = notificationAccess.isEnabled()),
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private val operationMutex = Mutex()
    private var bankPackagesInitialized = false

    init {
        repository.theme.onEach { value ->
            mutableState.value = mutableState.value.copy(theme = value)
        }.launchIn(viewModelScope)
        repository.wechatEnabled.onEach { value ->
            mutableState.value = mutableState.value.copy(wechatEnabled = value)
        }.launchIn(viewModelScope)
        repository.alipayEnabled.onEach { value ->
            mutableState.value = mutableState.value.copy(alipayEnabled = value)
        }.launchIn(viewModelScope)
        repository.enabledBankPackages.onEach { values ->
            if (!bankPackagesInitialized) {
                bankPackagesInitialized = true
                mutableState.value = mutableState.value.copy(
                    bankPackageDraft = values.sorted().joinToString("\n"),
                )
            }
        }.launchIn(viewModelScope)
        repository.categories.onEach { rows ->
            mutableState.value = mutableState.value.copy(categories = rows)
        }.launchIn(viewModelScope)
        repository.accounts.onEach { rows ->
            mutableState.value = mutableState.value.copy(accounts = rows)
        }.launchIn(viewModelScope)
        repository.transactionCount.onEach { count ->
            mutableState.value = mutableState.value.copy(transactionCount = count)
        }.launchIn(viewModelScope)
    }

    fun refreshNotificationAccess() {
        mutableState.value = mutableState.value.copy(
            notificationAccessEnabled = notificationAccess.isEnabled(),
        )
    }

    fun updateBankPackageDraft(value: String) {
        mutableState.value = mutableState.value.copy(bankPackageDraft = value, error = null)
    }

    fun reportNotificationSettingsLaunchFailure() {
        mutableState.value = mutableState.value.copy(
            error = "无法打开系统设置，请手动开启通知使用权",
        )
    }

    suspend fun setTheme(style: ThemeStyle): Result<Unit> = execute {
        repository.setTheme(style)
    }

    suspend fun setWechatEnabled(enabled: Boolean): Result<Unit> = execute {
        repository.setWechatEnabled(enabled)
    }

    suspend fun setAlipayEnabled(enabled: Boolean): Result<Unit> = execute {
        repository.setAlipayEnabled(enabled)
    }

    suspend fun saveBankPackages(): Result<Unit> {
        val packages = mutableState.value.bankPackageDraft
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (packages.any { !isValidBankPackage(it) }) {
            return validationFailure("请输入有效的银行应用包名，例如 com.example.bank")
        }
        return execute {
            repository.setEnabledBankPackages(packages.toCollection(linkedSetOf()))
        }
    }

    suspend fun addCategory(name: String): Result<Long> = executeValue {
        repository.addCategory(name)
    }

    suspend fun renameCategory(id: Long, name: String): Result<Unit> = execute {
        repository.renameCategory(id, name)
    }

    suspend fun setCategoryHidden(id: Long, hidden: Boolean): Result<Unit> = execute {
        repository.setCategoryHidden(id, hidden)
    }

    suspend fun moveCategory(id: Long, offset: Int): Result<Unit> = execute {
        repository.reorderCategories(movedIds(mutableState.value.categories.map { it.id }, id, offset))
    }

    suspend fun addAccount(name: String): Result<Long> = executeValue {
        repository.addAccount(name)
    }

    suspend fun renameAccount(id: Long, name: String): Result<Unit> = execute {
        repository.renameAccount(id, name)
    }

    suspend fun setAccountHidden(id: Long, hidden: Boolean): Result<Unit> = execute {
        repository.setAccountHidden(id, hidden)
    }

    suspend fun moveAccount(id: Long, offset: Int): Result<Unit> = execute {
        repository.reorderAccounts(movedIds(mutableState.value.accounts.map { it.id }, id, offset))
    }

    fun requestClearLedger() {
        mutableState.value = mutableState.value.copy(
            showFirstClearConfirmation = true,
            showFinalClearConfirmation = false,
            error = null,
        )
    }

    fun confirmFirstClearLedger() {
        if (!mutableState.value.showFirstClearConfirmation) return
        mutableState.value = mutableState.value.copy(
            showFirstClearConfirmation = false,
            showFinalClearConfirmation = true,
        )
    }

    fun dismissClearLedger() {
        mutableState.value = mutableState.value.copy(
            showFirstClearConfirmation = false,
            showFinalClearConfirmation = false,
        )
    }

    suspend fun confirmFinalClearLedger(): Result<Unit> {
        if (!mutableState.value.showFinalClearConfirmation) {
            return Result.failure(IllegalStateException("请先完成两次确认"))
        }
        mutableState.value = mutableState.value.copy(showFinalClearConfirmation = false)
        return execute { repository.clearLedger() }
    }

    private suspend fun execute(block: suspend () -> Unit): Result<Unit> =
        executeValue {
            block()
            Unit
        }

    private suspend fun <T> executeValue(block: suspend () -> T): Result<T> {
        if (!operationMutex.tryLock()) {
            return Result.failure(SettingsOperationInProgressException())
        }
        mutableState.value = mutableState.value.copy(busy = true, error = null)
        return try {
            Result.success(block())
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                error = when (failure) {
                    is SettingsValidationException ->
                        failure.message ?: "设置保存失败，请稍后重试"
                    else -> "设置保存失败，请稍后重试"
                },
            )
            Result.failure(failure)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operationMutex.unlock()
        }
    }

    private fun <T> validationFailure(message: String): Result<T> {
        mutableState.value = mutableState.value.copy(error = message)
        return Result.failure(IllegalArgumentException(message))
    }
}

class SettingsOperationInProgressException : IllegalStateException("正在处理，请稍候")

internal fun movedIds(ids: List<Long>, id: Long, offset: Int): List<Long> {
    val from = ids.indexOf(id)
    if (from == -1) throw IllegalArgumentException("设置项不存在")
    val to = (from + offset).coerceIn(ids.indices)
    if (from == to) return ids
    return ids.toMutableList().apply {
        removeAt(from)
        add(to, id)
    }
}
