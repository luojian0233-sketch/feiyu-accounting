package com.local.autobookkeeping.ui.settings

import com.local.autobookkeeping.data.AccountSetting
import com.local.autobookkeeping.data.CategorySetting
import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.ui.theme.ThemeStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
            categoryFailure = IllegalArgumentException("分类名称已存在"),
        )
        val viewModel = SettingsViewModel(repository) { false }

        val result = viewModel.addCategory("餐饮")

        assertTrue(result.isFailure)
        assertEquals("分类名称已存在", viewModel.state.value.error)
    }

    private class FakeSettingsOperations(
        initialCount: Int = 0,
        private val delayClear: Boolean = false,
        private val categoryFailure: Throwable? = null,
    ) : SettingsOperations {
        override val theme = MutableStateFlow(ThemeStyle.CLEAR_MINT)
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
            categoryFailure?.let { throw it }
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
}
