package com.local.autobookkeeping.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.autobookkeeping.backup.BackupOperations
import com.local.autobookkeeping.backup.RestoreResult
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
import java.io.InputStream
import java.io.OutputStream

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
    val backupStatus: String? = null,
    val backupStatusIsError: Boolean = false,
    val showBackupRestoreConfirmation: Boolean = false,
    val showFirstClearConfirmation: Boolean = false,
    val showFinalClearConfirmation: Boolean = false,
)

class SettingsViewModel(
    private val repository: SettingsOperations,
    private val backupOperations: BackupOperations? = null,
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

    fun requestBackupRestoreConfirmation() {
        mutableState.value = mutableState.value.copy(
            showBackupRestoreConfirmation = true,
            backupStatus = null,
        )
    }

    fun dismissBackupRestore() {
        mutableState.value = mutableState.value.copy(showBackupRestoreConfirmation = false)
    }

    fun reportBackupExportFailure() {
        mutableState.value = mutableState.value.copy(
            backupStatus = "备份导出失败，请稍后重试",
            backupStatusIsError = true,
        )
    }

    fun reportBackupRestoreFailure() {
        mutableState.value = mutableState.value.copy(
            backupStatus = "备份恢复失败，当前数据已保留",
            backupStatusIsError = true,
            showBackupRestoreConfirmation = false,
        )
    }

    suspend fun exportBackup(output: OutputStream): Result<Unit> {
        val backup = backupOperations
            ?: return Result.failure(IllegalStateException("备份功能未配置"))
        if (!operationMutex.tryLock()) {
            return Result.failure(SettingsOperationInProgressException())
        }
        mutableState.value = mutableState.value.copy(
            busy = true,
            backupStatus = null,
        )
        return try {
            backup.export(output)
            mutableState.value = mutableState.value.copy(
                backupStatus = "备份导出成功",
                backupStatusIsError = false,
            )
            Result.success(Unit)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            reportBackupExportFailure()
            Result.failure(failure)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operationMutex.unlock()
        }
    }

    suspend fun confirmBackupRestore(input: InputStream): Result<RestoreResult> {
        if (!mutableState.value.showBackupRestoreConfirmation) {
            return Result.failure(IllegalStateException("请先确认恢复操作"))
        }
        mutableState.value = mutableState.value.copy(showBackupRestoreConfirmation = false)
        val backup = backupOperations
            ?: return Result.failure(IllegalStateException("备份功能未配置"))
        if (!operationMutex.tryLock()) {
            return Result.failure(SettingsOperationInProgressException())
        }
        mutableState.value = mutableState.value.copy(busy = true, backupStatus = null)
        return try {
            val result = backup.restore(input)
            mutableState.value = mutableState.value.copy(
                backupStatus = when (result) {
                    is RestoreResult.Success ->
                        "备份恢复成功，共恢复 ${result.transactionCount} 笔交易"
                    is RestoreResult.Invalid -> "备份文件无效，未替换当前数据"
                    is RestoreResult.Failed -> "备份恢复失败，当前数据已保留"
                },
                backupStatusIsError = result !is RestoreResult.Success,
            )
            Result.success(result)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            reportBackupRestoreFailure()
            Result.failure(failure)
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operationMutex.unlock()
        }
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
