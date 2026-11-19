package com.local.autobookkeeping.ui.settings

import com.local.autobookkeeping.backup.BackupOperations
import com.local.autobookkeeping.backup.RestoreResult
import com.local.autobookkeeping.data.AccountSetting
import com.local.autobookkeeping.data.CategorySetting
import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.data.update.AppVersion
import com.local.autobookkeeping.data.update.LatestRelease
import com.local.autobookkeeping.data.update.UpdateCheckResult
import com.local.autobookkeeping.data.update.UpdateChecker
import com.local.autobookkeeping.ui.theme.ThemeStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `resume refresh shows missing accessibility without disabling manual bookkeeping`() {
        var notificationEnabled = true
        var accessibilityEnabled = false
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            notificationAccess = { notificationEnabled },
            accessibilityAccess = { accessibilityEnabled },
        )

        viewModel.refreshAutomaticAccess()

        assertTrue(viewModel.state.value.notificationAccessEnabled)
        assertFalse(viewModel.state.value.accessibilityAccessEnabled)
        assertEquals("页面识别服务未开启", viewModel.state.value.automaticAccessMessage)

        notificationEnabled = false
        accessibilityEnabled = true
        viewModel.refreshAutomaticAccess()
        assertEquals("通知读取权限未开启", viewModel.state.value.automaticAccessMessage)
    }

    @Test
    fun `automatic master and auto save mutations are independent`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations()
        val viewModel = SettingsViewModel(
            repository = repository,
            notificationAccess = { false },
            accessibilityAccess = { false },
        )

        assertTrue(viewModel.setAutoBookkeepingEnabled(true).isSuccess)
        assertTrue(viewModel.setAutoSaveHighConfidence(false).isSuccess)
        testScheduler.runCurrent()

        assertTrue(repository.autoBookkeepingEnabled.value)
        assertFalse(repository.autoSaveHighConfidence.value)
    }

    @Test
    fun `bank allowlist rejects any invalid package and preserves saved values`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations()
        val viewModel = SettingsViewModel(repository) { true }

        viewModel.updateBankPackageDraft("com.bank.one\nnot a package")
        val result = viewModel.saveBankPackages()

        assertTrue(result.isFailure)
        assertEquals(emptySet<String>(), repository.enabledBankPackages.value)
        assertEquals("请输入有效的银行应用包名，例如 com.example.bank", viewModel.state.value.error)
    }

    @Test
    fun `bank allowlist trims removes blank lines and saves exact packages`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations()
        val viewModel = SettingsViewModel(repository) { false }

        viewModel.updateBankPackageDraft(" com.bank.one \n\ncom.bank.two")
        val result = viewModel.saveBankPackages()

        assertTrue(result.isSuccess)
        assertEquals(setOf("com.bank.one", "com.bank.two"), repository.enabledBankPackages.value)
    }

    @Test
    fun `clear ledger requires two explicit confirmations and shows current count`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations(initialCount = 4)
        val viewModel = SettingsViewModel(repository) { false }
        testScheduler.runCurrent()

        viewModel.requestClearLedger()
        assertEquals(4, viewModel.state.value.transactionCount)
        assertTrue(viewModel.state.value.showFirstClearConfirmation)
        assertFalse(viewModel.state.value.showFinalClearConfirmation)
        assertEquals(0, repository.clearCalls)

        viewModel.confirmFirstClearLedger()
        assertFalse(viewModel.state.value.showFirstClearConfirmation)
        assertTrue(viewModel.state.value.showFinalClearConfirmation)
        assertEquals(0, repository.clearCalls)

        assertTrue(viewModel.confirmFinalClearLedger().isSuccess)
        assertEquals(1, repository.clearCalls)
    }

    @Test
    fun `rapid final clear confirmation executes repository once`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations(delayClear = true)
        val viewModel = SettingsViewModel(repository) { false }
        viewModel.requestClearLedger()
        viewModel.confirmFirstClearLedger()

        val first = async { viewModel.confirmFinalClearLedger() }
        testScheduler.runCurrent()
        val second = async { viewModel.confirmFinalClearLedger() }
        testScheduler.runCurrent()

        assertTrue(second.await().isFailure)
        assertEquals(1, repository.clearCalls)
        repository.clearRelease.complete(Unit)
        assertTrue(first.await().isSuccess)
    }

    @Test
    fun `repository validation errors are surfaced in Chinese`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations(
            operationFailure = com.local.autobookkeeping.data.SettingsValidationException("分类名称已存在"),
        )
        val viewModel = SettingsViewModel(repository) { false }

        val result = viewModel.addCategory("餐饮")

        assertTrue(result.isFailure)
        assertEquals("分类名称已存在", viewModel.state.value.error)
    }

    @Test
    fun `unexpected repository details are never exposed to user`() = runTest(dispatcher) {
        val repository = FakeSettingsOperations(
            operationFailure = IllegalStateException("/data/user/0 secret notification payload"),
        )
        val viewModel = SettingsViewModel(repository) { false }

        assertTrue(viewModel.addCategory("餐饮").isFailure)

        assertEquals("设置保存失败，请稍后重试", viewModel.state.value.error)
    }

    @Test
    fun `cancellation from repository is rethrown`() {
        val repository = FakeSettingsOperations(
            operationFailure = CancellationException("cancel"),
        )
        val viewModel = SettingsViewModel(repository) { false }

        assertThrows(CancellationException::class.java) {
            runTest(dispatcher) { viewModel.addCategory("餐饮") }
        }
    }

    @Test
    fun `notification settings failure uses stable Chinese message`() {
        val viewModel = SettingsViewModel(FakeSettingsOperations()) { false }

        viewModel.reportNotificationSettingsLaunchFailure()

        assertEquals("无法打开系统设置，请手动开启通知使用权", viewModel.state.value.error)
    }

    @Test
    fun `automatic recovery launch failures use stable Chinese without internal detail`() {
        val viewModel = SettingsViewModel(FakeSettingsOperations()) { false }

        viewModel.reportAutomaticSettingsLaunchFailure()
        assertEquals("无法打开系统设置，请手动检查自动记账权限", viewModel.state.value.error)

        viewModel.reportQuickSettingsTileFailure()
        assertEquals(
            "无法添加截屏记账按钮，请在快捷设置中手动添加",
            viewModel.state.value.error,
        )
        assertFalse(viewModel.state.value.error.orEmpty().contains("/data/"))
    }

    @Test
    fun `selected restore waits for explicit destructive confirmation before reading file`() = runTest(dispatcher) {
        val backup = FakeBackupOperations(restoreResult = RestoreResult.Success(3))
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = backup,
            notificationAccess = { false },
        )

        viewModel.requestBackupRestoreConfirmation()

        assertTrue(viewModel.state.value.showBackupRestoreConfirmation)
        assertEquals(0, backup.restoreCalls)

        val result = viewModel.confirmBackupRestore("{}".byteInputStream())

        assertTrue(result.isSuccess)
        assertFalse(viewModel.state.value.showBackupRestoreConfirmation)
        assertEquals(1, backup.restoreCalls)
        assertEquals("备份恢复成功，共恢复 3 笔交易", viewModel.state.value.backupStatus)
        assertFalse(viewModel.state.value.backupStatusIsError)
    }

    @Test
    fun `restore cannot be applied without confirmation`() = runTest(dispatcher) {
        val backup = FakeBackupOperations(restoreResult = RestoreResult.Success(1))
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = backup,
            notificationAccess = { false },
        )

        val result = viewModel.confirmBackupRestore("{}".byteInputStream())

        assertTrue(result.isFailure)
        assertEquals(0, backup.restoreCalls)
    }

    @Test
    fun `invalid and failed restore results use stable Chinese status`() = runTest(dispatcher) {
        val invalidBackup = FakeBackupOperations(
            restoreResult = RestoreResult.Invalid("internal validation detail"),
        )
        val invalidViewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = invalidBackup,
            notificationAccess = { false },
        )
        invalidViewModel.requestBackupRestoreConfirmation()

        assertTrue(invalidViewModel.confirmBackupRestore("{}".byteInputStream()).isSuccess)
        assertEquals("备份文件无效，未替换当前数据", invalidViewModel.state.value.backupStatus)
        assertTrue(invalidViewModel.state.value.backupStatusIsError)

        val failedBackup = FakeBackupOperations(
            restoreResult = RestoreResult.Failed("internal storage path"),
        )
        val failedViewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = failedBackup,
            notificationAccess = { false },
        )
        failedViewModel.requestBackupRestoreConfirmation()

        assertTrue(failedViewModel.confirmBackupRestore("{}".byteInputStream()).isSuccess)
        assertEquals("备份恢复失败，当前数据已保留", failedViewModel.state.value.backupStatus)
        assertTrue(failedViewModel.state.value.backupStatusIsError)
    }

    @Test
    fun `rollback failure status says data is uncertain without exposing diagnostics`() = runTest(dispatcher) {
        val backup = FakeBackupOperations(
            restoreResult = RestoreResult.RollbackFailed(
                IllegalStateException("/data/user/0/private.db rollback detail"),
            ),
        )
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = backup,
            notificationAccess = { false },
        )
        viewModel.requestBackupRestoreConfirmation()

        assertTrue(viewModel.confirmBackupRestore("{}".byteInputStream()).isSuccess)

        val status = viewModel.state.value.backupStatus.orEmpty()
        assertEquals(
            "自动回滚失败，数据状态不确定。请勿继续编辑，并重新尝试恢复备份。",
            status,
        )
        assertTrue(viewModel.state.value.backupStatusIsError)
        assertFalse(status.contains("当前数据已保留"))
        assertFalse(status.contains("/data/user"))
    }

    @Test
    fun `backup export uses stable Chinese success and failure status`() = runTest(dispatcher) {
        val successBackup = FakeBackupOperations()
        val successViewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = successBackup,
            notificationAccess = { false },
        )

        assertTrue(successViewModel.exportBackup(ByteArrayOutputStream()).isSuccess)
        assertEquals("备份导出成功", successViewModel.state.value.backupStatus)
        assertFalse(successViewModel.state.value.backupStatusIsError)

        val failedBackup = FakeBackupOperations(exportFailure = IllegalStateException("/secret/path"))
        val failedViewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = failedBackup,
            notificationAccess = { false },
        )

        assertTrue(failedViewModel.exportBackup(ByteArrayOutputStream()).isFailure)
        assertEquals("备份导出失败，请稍后重试", failedViewModel.state.value.backupStatus)
        assertTrue(failedViewModel.state.value.backupStatusIsError)
    }

    @Test
    fun `backup cancellation is rethrown`() {
        val exportViewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = FakeBackupOperations(
                exportFailure = CancellationException("cancel export"),
            ),
            notificationAccess = { false },
        )
        assertThrows(CancellationException::class.java) {
            runTest(dispatcher) { exportViewModel.exportBackup(ByteArrayOutputStream()) }
        }

        val restoreViewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            backupOperations = FakeBackupOperations(
                restoreFailure = CancellationException("cancel restore"),
            ),
            notificationAccess = { false },
        )
        restoreViewModel.requestBackupRestoreConfirmation()
        assertThrows(CancellationException::class.java) {
            runTest(dispatcher) {
                restoreViewModel.confirmBackupRestore("{}".byteInputStream())
            }
        }
    }

    @Test
    fun `update check exposes the newer release`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            notificationAccess = { false },
            updateChecker = UpdateChecker {
                Result.success(
                    UpdateCheckResult.Available(
                        LatestRelease(
                            versionName = "1.2.0",
                            versionCode = 2,
                            changelog = "· 分类抽屉",
                            downloadUrl = "https://example.com/app.apk",
                            releasePageUrl = "https://example.com",
                        ),
                    ),
                )
            },
            currentVersion = AppVersion(name = "1.0", code = 1),
        )

        viewModel.checkForUpdate()
        advanceUntilIdle()

        val available = assertInstanceOf(
            UpdateCheckState.Available::class.java,
            viewModel.state.value.updateState,
        )
        assertEquals("1.2.0", available.versionName)
        assertEquals("https://example.com/app.apk", available.downloadUrl)
    }

    @Test
    fun `settings always shows the installed version even before checking`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            notificationAccess = { false },
            updateChecker = UpdateChecker { Result.success(UpdateCheckResult.UpToDate) },
            currentVersion = AppVersion(name = "1.2.0", code = 2),
        )

        assertEquals("1.2.0", viewModel.state.value.currentVersionName)
        assertEquals(2, viewModel.state.value.currentVersionCode)

        viewModel.checkForUpdate()
        advanceUntilIdle()

        assertEquals(UpdateCheckState.UpToDate, viewModel.state.value.updateState)
    }

    /** 还没填 appUpdateRepo 时是「没配」，不该吓唬用户说网络出错。 */
    @Test
    fun `missing update source is not reported as a network failure`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            notificationAccess = { false },
        )

        viewModel.checkForUpdate()
        advanceUntilIdle()

        assertEquals(UpdateCheckState.Unconfigured, viewModel.state.value.updateState)
    }

    @Test
    fun `update check failure asks the user to retry`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(
            repository = FakeSettingsOperations(),
            notificationAccess = { false },
            updateChecker = UpdateChecker { Result.failure(IOException("offline")) },
            currentVersion = AppVersion(name = "1.0", code = 1),
        )

        viewModel.checkForUpdate()
        advanceUntilIdle()

        assertEquals(
            UpdateCheckState.Failed("检查失败，请确认网络后重试"),
            viewModel.state.value.updateState,
        )
    }

    private class FakeSettingsOperations(
        initialCount: Int = 0,
        private val delayClear: Boolean = false,
        private val operationFailure: Throwable? = null,
    ) : SettingsOperations {
        override val theme = MutableStateFlow(ThemeStyle.CLEAR_MINT)
        override val autoBookkeepingEnabled = MutableStateFlow(false)
        override val autoSaveHighConfidence = MutableStateFlow(true)
        override val wechatEnabled = MutableStateFlow(false)
        override val alipayEnabled = MutableStateFlow(false)
        override val enabledBankPackages = MutableStateFlow<Set<String>>(emptySet())
        override val categories = MutableStateFlow<List<CategorySetting>>(emptyList())
        override val visibleCategories = categories
        override val accounts = MutableStateFlow<List<AccountSetting>>(emptyList())
        override val visibleAccounts = accounts
        override val transactionCount = MutableStateFlow(initialCount)
        val clearRelease = CompletableDeferred<Unit>()
        var clearCalls = 0

        override suspend fun setTheme(style: ThemeStyle) {
            theme.value = style
        }

        override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) {
            autoBookkeepingEnabled.value = enabled
        }

        override suspend fun setAutoSaveHighConfidence(enabled: Boolean) {
            autoSaveHighConfidence.value = enabled
        }

        override suspend fun setWechatEnabled(enabled: Boolean) {
            wechatEnabled.value = enabled
        }

        override suspend fun setAlipayEnabled(enabled: Boolean) {
            alipayEnabled.value = enabled
        }

        override suspend fun setEnabledBankPackages(packages: Set<String>) {
            enabledBankPackages.value = packages
        }

        override suspend fun addCategory(name: String): Long {
            operationFailure?.let { throw it }
            return 1
        }

        override suspend fun renameCategory(id: Long, name: String) = Unit
        override suspend fun setCategoryHidden(id: Long, hidden: Boolean) = Unit
        override suspend fun reorderCategories(orderedIds: List<Long>) = Unit
        override suspend fun addAccount(name: String): Long = 1
        override suspend fun renameAccount(id: Long, name: String) = Unit
        override suspend fun setAccountHidden(id: Long, hidden: Boolean) = Unit
        override suspend fun reorderAccounts(orderedIds: List<Long>) = Unit

        override suspend fun clearLedger() {
            clearCalls += 1
            if (delayClear) clearRelease.await()
            transactionCount.value = 0
        }
    }

    private class FakeBackupOperations(
        private val restoreResult: RestoreResult = RestoreResult.Success(0),
        private val exportFailure: Throwable? = null,
        private val restoreFailure: Throwable? = null,
    ) : BackupOperations {
        var restoreCalls = 0

        override suspend fun export(output: OutputStream) {
            exportFailure?.let { throw it }
            output.write("{}".toByteArray())
        }

        override suspend fun restore(input: InputStream): RestoreResult {
            restoreCalls += 1
            restoreFailure?.let { throw it }
            return restoreResult
        }
    }
}
