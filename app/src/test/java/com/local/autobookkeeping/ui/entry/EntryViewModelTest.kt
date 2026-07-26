package com.local.autobookkeeping.ui.entry

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.AccountSetting
import com.local.autobookkeeping.data.CategorySetting
import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.ui.theme.ThemeStyle
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryViewModelTest {
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
    fun `rapid save executes repository once and returns Result`() = runTest(dispatcher) {
        val repository = DelayedRepository()
        val viewModel = EntryViewModel(repository)
        viewModel.update { it.copy(amountText = "28.00", category = "Food") }

        val first = async { viewModel.save() }
        testScheduler.runCurrent()
        val second = async { viewModel.save() }
        testScheduler.runCurrent()

        assertTrue(second.await().isFailure)
        assertEquals(1, repository.saveCalls)
        repository.release.complete(Unit)
        assertEquals(41L, first.await().getOrThrow())
    }

    @Test
    fun `rapid delete executes repository once`() = runTest(dispatcher) {
        val repository = DelayedRepository()
        val viewModel = EntryViewModel(repository)
        viewModel.edit(transaction(id = 9))

        val first = async { viewModel.delete() }
        testScheduler.runCurrent()
        val second = async { viewModel.delete() }
        testScheduler.runCurrent()

        assertTrue(second.await().isFailure)
        assertEquals(1, repository.deleteCalls)
        repository.release.complete(Unit)
        assertTrue(first.await().isSuccess)
    }

    @Test
    fun `editing historical row exposes current hidden category and account as marked history only choices`() =
        runTest(dispatcher) {
            val settings = FakeSettingsOperations()
            val viewModel = EntryViewModel(DelayedRepository(), settings)
            viewModel.edit(
                transaction(id = 18).copy(
                    category = "旧分类",
                    account = "旧账户",
                ),
            )

            testScheduler.runCurrent()

            assertEquals(
                listOf(
                    EntryChoice("餐饮", historicalOnly = false),
                    EntryChoice("旧分类", historicalOnly = true),
                ),
                viewModel.state.value.categoryChoices,
            )
            assertEquals(
                listOf(
                    EntryChoice("现金", historicalOnly = false),
                    EntryChoice("旧账户", historicalOnly = true),
                ),
                viewModel.state.value.accountChoices,
            )
        }

    @Test
    fun `managed new entry rejects unknown account but accepts no account`() = runTest(dispatcher) {
        val repository = DelayedRepository(releaseImmediately = true)
        val viewModel = EntryViewModel(repository, FakeSettingsOperations())
        testScheduler.runCurrent()
        viewModel.startNew()
        viewModel.update {
            it.copy(amountText = "12.00", category = "餐饮", account = "隐藏账户")
        }

        val invalid = viewModel.save()

        assertTrue(invalid.isFailure)
        assertEquals("请选择可用账户或不选择账户", viewModel.state.value.error)
        assertEquals(0, repository.saveCalls)

        viewModel.update { it.copy(account = "") }
        assertTrue(viewModel.save().isSuccess)
        assertEquals(1, repository.saveCalls)
    }

    private fun transaction(id: Long) = Transaction(
        id = id,
        amountMinor = 100,
        direction = Direction.EXPENSE,
        category = "Food",
        occurredAt = Instant.parse("2026-07-22T04:00:00Z"),
    )

    private class DelayedRepository(
        releaseImmediately: Boolean = false,
    ) : LedgerRepository {
        val release = CompletableDeferred<Unit>()
        var saveCalls = 0
        var deleteCalls = 0
        private val rows = MutableStateFlow<List<Transaction>>(emptyList())

        init {
            if (releaseImmediately) release.complete(Unit)
        }

        override fun observeTransactions(): Flow<List<Transaction>> = rows
        override suspend fun save(transaction: Transaction): Long {
            saveCalls += 1
            release.await()
            return 41
        }

        override suspend fun delete(id: Long) {
            deleteCalls += 1
            release.await()
        }

        override suspend fun findByDedupeKey(key: String): Transaction? = null
        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) = Unit
    }

    private class FakeSettingsOperations : SettingsOperations {
        override val theme = MutableStateFlow(ThemeStyle.CLEAR_MINT)
        override val wechatEnabled = MutableStateFlow(false)
        override val alipayEnabled = MutableStateFlow(false)
        override val enabledBankPackages = MutableStateFlow<Set<String>>(emptySet())
        override val categories = MutableStateFlow(
            listOf(
                CategorySetting(1, "餐饮", 0, hidden = false),
                CategorySetting(2, "旧分类", 1, hidden = true),
            ),
        )
        override val visibleCategories = MutableStateFlow(categories.value.filterNot { it.hidden })
        override val accounts = MutableStateFlow(
            listOf(
                AccountSetting(1, "现金", "CNY", 0, hidden = false),
                AccountSetting(2, "旧账户", "CNY", 1, hidden = true),
            ),
        )
        override val visibleAccounts = MutableStateFlow(accounts.value.filterNot { it.hidden })
        override val transactionCount = MutableStateFlow(0)
        override suspend fun setTheme(style: ThemeStyle) = Unit
        override suspend fun setWechatEnabled(enabled: Boolean) = Unit
        override suspend fun setAlipayEnabled(enabled: Boolean) = Unit
        override suspend fun setEnabledBankPackages(packages: Set<String>) = Unit
        override suspend fun addCategory(name: String): Long = 0
        override suspend fun renameCategory(id: Long, name: String) = Unit
        override suspend fun setCategoryHidden(id: Long, hidden: Boolean) = Unit
        override suspend fun reorderCategories(orderedIds: List<Long>) = Unit
        override suspend fun addAccount(name: String): Long = 0
        override suspend fun renameAccount(id: Long, name: String) = Unit
        override suspend fun setAccountHidden(id: Long, hidden: Boolean) = Unit
        override suspend fun reorderAccounts(orderedIds: List<Long>) = Unit
        override suspend fun clearLedger() = Unit
    }
}
