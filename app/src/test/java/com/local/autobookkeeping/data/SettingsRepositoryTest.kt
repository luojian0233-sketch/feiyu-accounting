package com.local.autobookkeeping.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.local.autobookkeeping.ui.theme.ThemeStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsRepositoryTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `automatic settings default to off except high confidence auto save`() = runTest {
        val repository = SettingsRepository(testDataStore("automatic-defaults.preferences_pb"))

        assertFalse(repository.autoBookkeepingEnabled.first())
        assertFalse(repository.wechatEnabled.first())
        assertFalse(repository.alipayEnabled.first())
        assertTrue(repository.autoSaveHighConfidence.first())
    }

    @Test
    fun `absent total switch migrates an existing enabled source once`() = runTest {
        val dataStore = testDataStore("automatic-migration.preferences_pb")
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("wechat_enabled")] = true
        }
        val repository = SettingsRepository(dataStore)

        assertTrue(repository.autoBookkeepingEnabled.first())

        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("wechat_enabled")] = false
        }
        assertTrue(repository.autoBookkeepingEnabled.first())
    }

    @Test
    fun `explicit false total switch is never overwritten by enabled legacy source`() = runTest {
        val dataStore = testDataStore("automatic-explicit-false.preferences_pb")
        dataStore.edit { preferences ->
            preferences[booleanPreferencesKey("auto_bookkeeping_enabled")] = false
            preferences[booleanPreferencesKey("alipay_enabled")] = true
        }

        assertFalse(SettingsRepository(dataStore).autoBookkeepingEnabled.first())
    }

    @Test
    fun `absent total switch migrates alipay and bank legacy sources`() = runTest {
        val alipayStore = testDataStore("automatic-alipay-migration.preferences_pb")
        alipayStore.edit { preferences ->
            preferences[booleanPreferencesKey("alipay_enabled")] = true
        }
        val bankStore = testDataStore("automatic-bank-migration.preferences_pb")
        bankStore.edit { preferences ->
            preferences[stringSetPreferencesKey("enabled_bank_packages")] =
                setOf("com.example.bank")
        }

        assertTrue(SettingsRepository(alipayStore).autoBookkeepingEnabled.first())
        assertTrue(SettingsRepository(bankStore).autoBookkeepingEnabled.first())
    }

    @Test
    fun `automatic preferences persist repository mutations`() = runTest {
        val dataStore = testDataStore("automatic-mutations.preferences_pb")
        val repository = SettingsRepository(dataStore)

        repository.setAutoBookkeepingEnabled(true)
        repository.setAutoSaveHighConfidence(false)

        val recreated = SettingsRepository(dataStore)
        assertTrue(recreated.autoBookkeepingEnabled.first())
        assertFalse(recreated.autoSaveHighConfidence.first())
    }

    @Test
    fun `high confidence auto save fails closed when preference flow throws IOException`() =
        runTest {
            val repository = SettingsRepository(
                object : DataStore<Preferences> {
                    override val data = flow<Preferences> {
                        throw IOException("unreadable preference file")
                    }

                    override suspend fun updateData(
                        transform: suspend (t: Preferences) -> Preferences,
                    ): Preferences = throw IOException("unreadable preference file")
                },
            )

            assertFalse(repository.autoSaveHighConfidence.first())
        }

    @Test
    fun `theme defaults to clear mint and survives repository recreation`() = runTest {
        val preferences = InMemorySettingsPreferenceStore()

        assertEquals(
            ThemeStyle.CLEAR_MINT,
            SettingsRepository(preferences, InMemoryLocalSettingsStore()).theme.first(),
        )

        SettingsRepository(preferences, InMemoryLocalSettingsStore())
            .setTheme(ThemeStyle.PAPER_MINIMAL)

        assertEquals(
            ThemeStyle.PAPER_MINIMAL,
            SettingsRepository(preferences, InMemoryLocalSettingsStore()).theme.first(),
        )
    }

    @Test
    fun `category names are trimmed nonblank and unique while hidden rows remain resolvable`() = runTest {
        val store = InMemoryLocalSettingsStore(
            categoryRows = listOf(
                CategorySetting(id = 1, name = "餐饮", displayOrder = 0, hidden = false),
            ),
        )
        val repository = SettingsRepository(InMemorySettingsPreferenceStore(), store)

        val id = repository.addCategory("  红包  ")
        repository.setCategoryHidden(id, true)

        assertEquals(listOf("餐饮"), repository.visibleCategories.first().map { it.name })
        assertEquals(listOf("餐饮", "红包"), repository.categories.first().map { it.name })
        assertTrue(repository.categories.first().single { it.id == id }.hidden)
        assertEquals("红包", store.categoryRows.single { it.id == id }.name)
        expectValidation("分类名称不能为空") { repository.addCategory("   ") }
        expectValidation("分类名称已存在") { repository.renameCategory(id, "餐饮") }
    }

    @Test
    fun `category and account reorder writes deterministic complete order atomically`() = runTest {
        val store = InMemoryLocalSettingsStore(
            categoryRows = listOf(
                CategorySetting(11, "餐饮", 0, false),
                CategorySetting(12, "交通", 1, false),
                CategorySetting(13, "红包", 2, true),
            ),
            accountRows = listOf(
                AccountSetting(21, "现金", "CNY", 0, false),
                AccountSetting(22, "银行卡", "CNY", 1, false),
            ),
        )
        val repository = SettingsRepository(InMemorySettingsPreferenceStore(), store)

        repository.reorderCategories(listOf(12, 11, 13))
        repository.reorderAccounts(listOf(22, 21))

        assertEquals(listOf(12L, 11L, 13L), store.lastCategoryReorder)
        assertEquals(listOf(22L, 21L), store.lastAccountReorder)
        assertEquals(listOf(0, 1, 2), repository.categories.first().map { it.displayOrder })
        assertEquals(listOf(0, 1), repository.accounts.first().map { it.displayOrder })
        expectValidation("分类排序数据无效") {
            repository.reorderCategories(listOf(11, 12))
        }
    }

    @Test
    fun `accounts validate names and hidden accounts disappear only from new entry choices`() = runTest {
        val store = InMemoryLocalSettingsStore()
        val repository = SettingsRepository(InMemorySettingsPreferenceStore(), store)

        val cashId = repository.addAccount(" 现金 ")
        val bankId = repository.addAccount("银行卡")
        repository.setAccountHidden(bankId, true)

        assertEquals(listOf("现金"), repository.visibleAccounts.first().map { it.name })
        assertEquals(listOf("现金", "银行卡"), repository.accounts.first().map { it.name })
        expectValidation("账户名称已存在") {
            repository.renameAccount(bankId, " 现金 ")
        }
        assertFalse(repository.accounts.first().single { it.id == cashId }.hidden)
    }

    @Test
    fun `clear ledger deletes only transactions and preserves preferences and configuration`() = runTest {
        val preferences = InMemorySettingsPreferenceStore()
        val store = InMemoryLocalSettingsStore(
            categoryRows = listOf(CategorySetting(1, "餐饮", 0, false)),
            accountRows = listOf(AccountSetting(2, "现金", "CNY", 0, false)),
            initialTransactionCount = 3,
        )
        val repository = SettingsRepository(preferences, store)
        repository.setTheme(ThemeStyle.RECEIPT_LEDGER)
        repository.setWechatEnabled(true)

        repository.clearLedger()

        assertEquals(0, repository.transactionCount.first())
        assertEquals(listOf("餐饮"), repository.categories.first().map { it.name })
        assertEquals(listOf("现金"), repository.accounts.first().map { it.name })
        assertEquals(ThemeStyle.RECEIPT_LEDGER, repository.theme.first())
        assertTrue(repository.wechatEnabled.first())
        assertEquals(1, store.clearTransactionsCalls)
    }

    @Test
    fun `preference reads recover IOException to empty preferences`() = runTest {
        val recovered = recoverPreferenceReads(
            flow {
                throw IOException("corrupt path and internal detail")
            },
        ).first()

        assertEquals(emptyPreferences(), recovered)
    }

    @Test
    fun `preference reads rethrow cancellation and unexpected exceptions`() = runTest {
        expectFailure<CancellationException> {
            recoverPreferenceReads(flow { throw CancellationException("cancel") }).first()
        }
        expectFailure<IllegalStateException> {
            recoverPreferenceReads(flow { throw IllegalStateException("bug") }).first()
        }
    }

    @Test
    fun `repository mutations rethrow cancellation and unexpected failures`() = runTest {
        val cancelledRepository = SettingsRepository(
            InMemorySettingsPreferenceStore(),
            InMemoryLocalSettingsStore(
                insertCategoryFailure = CancellationException("cancel"),
            ),
        )

        expectFailure<CancellationException> {
            cancelledRepository.addCategory("餐饮")
        }

        val failedRepository = SettingsRepository(
            InMemorySettingsPreferenceStore(),
            InMemoryLocalSettingsStore(
                insertCategoryFailure = IllegalStateException("sensitive internal detail"),
            ),
        )

        expectFailure<IllegalStateException> {
            failedRepository.addCategory("餐饮")
        }
    }

    private suspend fun expectValidation(
        expectedMessage: String,
        block: suspend () -> Unit,
    ) {
        val failure = try {
            block()
            fail<SettingsValidationException>("Expected SettingsValidationException")
        } catch (failure: SettingsValidationException) {
            failure
        }
        assertEquals(expectedMessage, failure.message)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ) {
        try {
            block()
            fail<T>("Expected ${T::class.simpleName}")
        } catch (failure: Throwable) {
            assertTrue(failure is T)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.testDataStore(fileName: String) =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tempDirectory.resolve(fileName).toFile() },
        )
}

private class InMemorySettingsPreferenceStore : SettingsPreferenceStore {
    override val autoBookkeepingEnabled = MutableStateFlow(false)
    override val autoSaveHighConfidence = MutableStateFlow(true)
    override val wechatEnabled = MutableStateFlow(false)
    override val alipayEnabled = MutableStateFlow(false)
    override val enabledBankPackages = MutableStateFlow<Set<String>>(emptySet())
    override val theme = MutableStateFlow(ThemeStyle.CLEAR_MINT)

    override suspend fun setWechatEnabled(enabled: Boolean) {
        wechatEnabled.value = enabled
    }

    override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) {
        autoBookkeepingEnabled.value = enabled
    }

    override suspend fun setAutoSaveHighConfidence(enabled: Boolean) {
        autoSaveHighConfidence.value = enabled
    }

    override suspend fun setAlipayEnabled(enabled: Boolean) {
        alipayEnabled.value = enabled
    }

    override suspend fun setEnabledBankPackages(packages: Set<String>) {
        enabledBankPackages.value = packages
    }

    override suspend fun setTheme(style: ThemeStyle) {
        theme.value = style
    }
}

private class InMemoryLocalSettingsStore(
    categoryRows: List<CategorySetting> = emptyList(),
    accountRows: List<AccountSetting> = emptyList(),
    initialTransactionCount: Int = 0,
    private val insertCategoryFailure: Throwable? = null,
) : LocalSettingsStore {
    val categoryRows = categoryRows.toMutableList()
    val accountRows = accountRows.toMutableList()
    override val categories = MutableStateFlow(this.categoryRows.toList())
    override val accounts = MutableStateFlow(this.accountRows.toList())
    override val transactionCount = MutableStateFlow(initialTransactionCount)
    var lastCategoryReorder: List<Long> = emptyList()
    var lastAccountReorder: List<Long> = emptyList()
    var clearTransactionsCalls = 0

    override suspend fun insertCategory(name: String): Long {
        insertCategoryFailure?.let { throw it }
        val id = (categoryRows.maxOfOrNull { it.id } ?: 0) + 1
        categoryRows += CategorySetting(id, name, categoryRows.size, false)
        publishCategories()
        return id
    }

    override suspend fun renameCategory(id: Long, name: String) {
        val index = categoryRows.indexOfFirst { it.id == id }
        categoryRows[index] = categoryRows[index].copy(name = name)
        publishCategories()
    }

    override suspend fun setCategoryHidden(id: Long, hidden: Boolean) {
        val index = categoryRows.indexOfFirst { it.id == id }
        categoryRows[index] = categoryRows[index].copy(hidden = hidden)
        publishCategories()
    }

    override suspend fun reorderCategories(orderedIds: List<Long>) {
        lastCategoryReorder = orderedIds
        val byId = categoryRows.associateBy { it.id }
        categoryRows.clear()
        categoryRows += orderedIds.mapIndexed { order, id ->
            checkNotNull(byId[id]).copy(displayOrder = order)
        }
        publishCategories()
    }

    override suspend fun insertAccount(name: String): Long {
        val id = (accountRows.maxOfOrNull { it.id } ?: 0) + 1
        accountRows += AccountSetting(id, name, "CNY", accountRows.size, false)
        publishAccounts()
        return id
    }

    override suspend fun renameAccount(id: Long, name: String) {
        val index = accountRows.indexOfFirst { it.id == id }
        accountRows[index] = accountRows[index].copy(name = name)
        publishAccounts()
    }

    override suspend fun setAccountHidden(id: Long, hidden: Boolean) {
        val index = accountRows.indexOfFirst { it.id == id }
        accountRows[index] = accountRows[index].copy(hidden = hidden)
        publishAccounts()
    }

    override suspend fun reorderAccounts(orderedIds: List<Long>) {
        lastAccountReorder = orderedIds
        val byId = accountRows.associateBy { it.id }
        accountRows.clear()
        accountRows += orderedIds.mapIndexed { order, id ->
            checkNotNull(byId[id]).copy(displayOrder = order)
        }
        publishAccounts()
    }

    override suspend fun clearTransactions() {
        clearTransactionsCalls += 1
        transactionCount.value = 0
    }

    private fun publishCategories() {
        categories.value = categoryRows.toList()
    }

    private fun publishAccounts() {
        accounts.value = accountRows.toList()
    }
}
