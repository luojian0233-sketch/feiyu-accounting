package com.local.autobookkeeping.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.local.autobookkeeping.data.db.AccountEntity
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.data.db.CategoryEntity
import com.local.autobookkeeping.data.db.nextDisplayOrder
import com.local.autobookkeeping.ui.theme.ThemeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException

interface NotificationSourceSettings {
    val autoBookkeepingEnabled: Flow<Boolean>
    val wechatEnabled: Flow<Boolean>
    val alipayEnabled: Flow<Boolean>
    val enabledBankPackages: Flow<Set<String>>
}

data class CategorySetting(
    val id: Long,
    val name: String,
    val displayOrder: Int,
    val hidden: Boolean,
)

data class AccountSetting(
    val id: Long,
    val name: String,
    val currency: String,
    val displayOrder: Int,
    val hidden: Boolean,
)

interface SettingsOperations : NotificationSourceSettings {
    val theme: Flow<ThemeStyle>
    val autoSaveHighConfidence: Flow<Boolean>
    val categories: Flow<List<CategorySetting>>
    val visibleCategories: Flow<List<CategorySetting>>
    val accounts: Flow<List<AccountSetting>>
    val visibleAccounts: Flow<List<AccountSetting>>
    val transactionCount: Flow<Int>

    suspend fun setTheme(style: ThemeStyle)
    suspend fun setAutoBookkeepingEnabled(enabled: Boolean)
    suspend fun setAutoSaveHighConfidence(enabled: Boolean)
    suspend fun setWechatEnabled(enabled: Boolean)
    suspend fun setAlipayEnabled(enabled: Boolean)
    suspend fun setEnabledBankPackages(packages: Set<String>)
    suspend fun addCategory(name: String): Long
    suspend fun renameCategory(id: Long, name: String)
    suspend fun setCategoryHidden(id: Long, hidden: Boolean)
    suspend fun reorderCategories(orderedIds: List<Long>)
    suspend fun addAccount(name: String): Long
    suspend fun renameAccount(id: Long, name: String)
    suspend fun setAccountHidden(id: Long, hidden: Boolean)
    suspend fun reorderAccounts(orderedIds: List<Long>)
    suspend fun clearLedger()
}

class SettingsValidationException(message: String) : IllegalArgumentException(message)

internal interface SettingsPreferenceStore : NotificationSourceSettings {
    val theme: Flow<ThemeStyle>
    val autoSaveHighConfidence: Flow<Boolean>
    suspend fun setTheme(style: ThemeStyle)
    suspend fun setAutoBookkeepingEnabled(enabled: Boolean)
    suspend fun setAutoSaveHighConfidence(enabled: Boolean)
    suspend fun setWechatEnabled(enabled: Boolean)
    suspend fun setAlipayEnabled(enabled: Boolean)
    suspend fun setEnabledBankPackages(packages: Set<String>)
}

internal interface LocalSettingsStore {
    val categories: Flow<List<CategorySetting>>
    val accounts: Flow<List<AccountSetting>>
    val transactionCount: Flow<Int>
    suspend fun insertCategory(name: String): Long
    suspend fun renameCategory(id: Long, name: String)
    suspend fun setCategoryHidden(id: Long, hidden: Boolean)
    suspend fun reorderCategories(orderedIds: List<Long>)
    suspend fun insertAccount(name: String): Long
    suspend fun renameAccount(id: Long, name: String)
    suspend fun setAccountHidden(id: Long, hidden: Boolean)
    suspend fun reorderAccounts(orderedIds: List<Long>)
    suspend fun clearTransactions()
}

private val Context.notificationSettingsDataStore by preferencesDataStore(
    name = "notification_source_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository internal constructor(
    private val preferences: SettingsPreferenceStore,
    private val localSettings: LocalSettingsStore,
) : SettingsOperations {
    constructor(
        context: Context,
        database: AppDatabase? = null,
    ) : this(
        preferences = DataStoreSettingsPreferenceStore(
            context.applicationContext.notificationSettingsDataStore,
        ),
        localSettings = database?.let(::RoomLocalSettingsStore) ?: EmptyLocalSettingsStore,
    )

    constructor(dataStore: DataStore<Preferences>) : this(
        preferences = DataStoreSettingsPreferenceStore(dataStore),
        localSettings = EmptyLocalSettingsStore,
    )

    constructor(
        dataStore: DataStore<Preferences>,
        database: AppDatabase,
    ) : this(
        preferences = DataStoreSettingsPreferenceStore(dataStore),
        localSettings = RoomLocalSettingsStore(database),
    )

    override val autoBookkeepingEnabled: Flow<Boolean> = preferences.autoBookkeepingEnabled
    override val autoSaveHighConfidence: Flow<Boolean> = preferences.autoSaveHighConfidence
    override val wechatEnabled: Flow<Boolean> = preferences.wechatEnabled
    override val alipayEnabled: Flow<Boolean> = preferences.alipayEnabled
    override val enabledBankPackages: Flow<Set<String>> = preferences.enabledBankPackages
    override val theme: Flow<ThemeStyle> = preferences.theme
    override val categories: Flow<List<CategorySetting>> = localSettings.categories
    override val visibleCategories: Flow<List<CategorySetting>> =
        categories.map { rows -> rows.filterNot(CategorySetting::hidden) }.distinctUntilChanged()
    override val accounts: Flow<List<AccountSetting>> = localSettings.accounts
    override val visibleAccounts: Flow<List<AccountSetting>> =
        accounts.map { rows -> rows.filterNot(AccountSetting::hidden) }.distinctUntilChanged()
    override val transactionCount: Flow<Int> = localSettings.transactionCount

    override suspend fun setTheme(style: ThemeStyle) = preferences.setTheme(style)
    override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) =
        preferences.setAutoBookkeepingEnabled(enabled)
    override suspend fun setAutoSaveHighConfidence(enabled: Boolean) =
        preferences.setAutoSaveHighConfidence(enabled)
    override suspend fun setWechatEnabled(enabled: Boolean) = preferences.setWechatEnabled(enabled)
    override suspend fun setAlipayEnabled(enabled: Boolean) = preferences.setAlipayEnabled(enabled)

    override suspend fun setEnabledBankPackages(packages: Set<String>) {
        preferences.setEnabledBankPackages(sanitizeBankPackages(packages))
    }

    override suspend fun addCategory(name: String): Long {
        val normalized = validatedName(name, "分类")
        ensureUnique(normalized, categories.first().map(CategorySetting::name), "分类")
        return settingsMutation("分类名称已存在") { localSettings.insertCategory(normalized) }
    }

    override suspend fun renameCategory(id: Long, name: String) {
        val normalized = validatedName(name, "分类")
        ensureUnique(
            normalized,
            categories.first().filterNot { it.id == id }.map(CategorySetting::name),
            "分类",
        )
        settingsMutation("分类名称已存在") { localSettings.renameCategory(id, normalized) }
    }

    override suspend fun setCategoryHidden(id: Long, hidden: Boolean) {
        localSettings.setCategoryHidden(id, hidden)
    }

    override suspend fun reorderCategories(orderedIds: List<Long>) {
        validateCompleteOrder(
            orderedIds = orderedIds,
            existingIds = categories.first().map(CategorySetting::id),
            itemLabel = "分类",
        )
        localSettings.reorderCategories(orderedIds)
    }

    override suspend fun addAccount(name: String): Long {
        val normalized = validatedName(name, "账户")
        ensureUnique(normalized, accounts.first().map(AccountSetting::name), "账户")
        return settingsMutation("账户名称已存在") { localSettings.insertAccount(normalized) }
    }

    override suspend fun renameAccount(id: Long, name: String) {
        val normalized = validatedName(name, "账户")
        ensureUnique(
            normalized,
            accounts.first().filterNot { it.id == id }.map(AccountSetting::name),
            "账户",
        )
        settingsMutation("账户名称已存在") { localSettings.renameAccount(id, normalized) }
    }

    override suspend fun setAccountHidden(id: Long, hidden: Boolean) {
        localSettings.setAccountHidden(id, hidden)
    }

    override suspend fun reorderAccounts(orderedIds: List<Long>) {
        validateCompleteOrder(
            orderedIds = orderedIds,
            existingIds = accounts.first().map(AccountSetting::id),
            itemLabel = "账户",
        )
        localSettings.reorderAccounts(orderedIds)
    }

    override suspend fun clearLedger() {
        localSettings.clearTransactions()
    }

    companion object {
        internal const val DEFAULT_SOURCE_ENABLED = false
        internal const val DEFAULT_AUTO_SAVE_HIGH_CONFIDENCE = true
    }
}

private class DataStoreSettingsPreferenceStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsPreferenceStore {
    override val autoBookkeepingEnabled: Flow<Boolean> = migratedAutoBookkeepingSetting()
    override val autoSaveHighConfidence: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[AUTO_SAVE_HIGH_CONFIDENCE]
                ?: SettingsRepository.DEFAULT_AUTO_SAVE_HIGH_CONFIDENCE
        }
        .catch { failure ->
            if (failure is IOException) emit(false) else throw failure
        }
        .distinctUntilChanged()
    override val wechatEnabled: Flow<Boolean> = booleanSetting(WECHAT_ENABLED)
    override val alipayEnabled: Flow<Boolean> = booleanSetting(ALIPAY_ENABLED)
    override val enabledBankPackages: Flow<Set<String>> = recoverPreferenceReads(dataStore.data)
        .map { preferences ->
            preferences[ENABLED_BANK_PACKAGES].orEmpty().let(::sanitizeBankPackages)
        }
        .distinctUntilChanged()
    override val theme: Flow<ThemeStyle> = recoverPreferenceReads(dataStore.data)
        .map { preferences ->
            preferences[THEME_STYLE]
                ?.let { stored -> ThemeStyle.entries.firstOrNull { it.name == stored } }
                ?: ThemeStyle.CLEAR_MINT
        }
        .distinctUntilChanged()

    override suspend fun setWechatEnabled(enabled: Boolean) = setBoolean(WECHAT_ENABLED, enabled)
    override suspend fun setAlipayEnabled(enabled: Boolean) = setBoolean(ALIPAY_ENABLED, enabled)
    override suspend fun setAutoBookkeepingEnabled(enabled: Boolean) =
        setBoolean(AUTO_BOOKKEEPING_ENABLED, enabled)
    override suspend fun setAutoSaveHighConfidence(enabled: Boolean) =
        setBoolean(AUTO_SAVE_HIGH_CONFIDENCE, enabled)

    override suspend fun setEnabledBankPackages(packages: Set<String>) {
        dataStore.edit { preferences -> preferences[ENABLED_BANK_PACKAGES] = packages }
    }

    override suspend fun setTheme(style: ThemeStyle) {
        dataStore.edit { preferences -> preferences[THEME_STYLE] = style.name }
    }

    private fun booleanSetting(
        key: Preferences.Key<Boolean>,
        default: Boolean = SettingsRepository.DEFAULT_SOURCE_ENABLED,
    ): Flow<Boolean> =
        recoverPreferenceReads(dataStore.data)
        .map { preferences -> preferences[key] ?: default }
        .distinctUntilChanged()

    private fun migratedAutoBookkeepingSetting(): Flow<Boolean> = flow {
        val initial = recoverPreferenceReads(dataStore.data).first()
        if (!initial.contains(AUTO_BOOKKEEPING_ENABLED)) {
            dataStore.edit { latest ->
                if (!latest.contains(AUTO_BOOKKEEPING_ENABLED)) {
                    latest[AUTO_BOOKKEEPING_ENABLED] = latest[WECHAT_ENABLED] == true ||
                        latest[ALIPAY_ENABLED] == true ||
                        sanitizeBankPackages(
                            latest[ENABLED_BANK_PACKAGES].orEmpty(),
                        ).isNotEmpty()
                }
            }
        }
        emitAll(booleanSetting(AUTO_BOOKKEEPING_ENABLED))
    }.distinctUntilChanged()

    private suspend fun setBoolean(key: Preferences.Key<Boolean>, enabled: Boolean) {
        dataStore.edit { preferences -> preferences[key] = enabled }
    }

    private companion object {
        val AUTO_BOOKKEEPING_ENABLED = booleanPreferencesKey("auto_bookkeeping_enabled")
        val AUTO_SAVE_HIGH_CONFIDENCE = booleanPreferencesKey("auto_save_high_confidence")
        val WECHAT_ENABLED = booleanPreferencesKey("wechat_enabled")
        val ALIPAY_ENABLED = booleanPreferencesKey("alipay_enabled")
        val ENABLED_BANK_PACKAGES = stringSetPreferencesKey("enabled_bank_packages")
        val THEME_STYLE = stringPreferencesKey("theme_style")
    }
}

private object EmptyLocalSettingsStore : LocalSettingsStore {
    override val categories = flowOf(emptyList<CategorySetting>())
    override val accounts = flowOf(emptyList<AccountSetting>())
    override val transactionCount = flowOf(0)
    override suspend fun insertCategory(name: String): Long = unavailable()
    override suspend fun renameCategory(id: Long, name: String) = unavailable()
    override suspend fun setCategoryHidden(id: Long, hidden: Boolean) = unavailable()
    override suspend fun reorderCategories(orderedIds: List<Long>) = unavailable()
    override suspend fun insertAccount(name: String): Long = unavailable()
    override suspend fun renameAccount(id: Long, name: String) = unavailable()
    override suspend fun setAccountHidden(id: Long, hidden: Boolean) = unavailable()
    override suspend fun reorderAccounts(orderedIds: List<Long>) = unavailable()
    override suspend fun clearTransactions() = unavailable()

    private fun unavailable(): Nothing = error("本地设置数据库未配置")
}

private class RoomLocalSettingsStore(
    private val database: AppDatabase,
) : LocalSettingsStore {
    private val dao = database.transactionDao()
    override val categories: Flow<List<CategorySetting>> = dao.observeCategories()
        .map { rows -> rows.map(CategoryEntity::toSetting) }
    override val accounts: Flow<List<AccountSetting>> = dao.observeAccounts()
        .map { rows -> rows.map(AccountEntity::toSetting) }
    override val transactionCount: Flow<Int> = dao.observeTransactionCount()

    override suspend fun insertCategory(name: String): Long = database.withTransaction {
        val order = nextDisplayOrder(dao.categoryDisplayOrders())
        dao.insertCategory(CategoryEntity(name = name, displayOrder = order)).also {
            if (it == -1L) throw SettingsValidationException("分类名称已存在")
        }
    }

    override suspend fun renameCategory(id: Long, name: String) {
        check(dao.renameCategory(id, name) == 1) { "分类不存在" }
    }

    override suspend fun setCategoryHidden(id: Long, hidden: Boolean) {
        check(dao.setCategoryHidden(id, hidden) == 1) { "分类不存在" }
    }

    override suspend fun reorderCategories(orderedIds: List<Long>) {
        database.withTransaction {
            orderedIds.forEachIndexed { order, id ->
                check(dao.setCategoryDisplayOrder(id, order) == 1) { "分类不存在" }
            }
        }
    }

    override suspend fun insertAccount(name: String): Long = database.withTransaction {
        val order = nextDisplayOrder(dao.accountDisplayOrders())
        dao.insertAccount(AccountEntity(name = name, displayOrder = order)).also {
            if (it == -1L) throw SettingsValidationException("账户名称已存在")
        }
    }

    override suspend fun renameAccount(id: Long, name: String) {
        check(dao.renameAccount(id, name) == 1) { "账户不存在" }
    }

    override suspend fun setAccountHidden(id: Long, hidden: Boolean) {
        check(dao.setAccountHidden(id, hidden) == 1) { "账户不存在" }
    }

    override suspend fun reorderAccounts(orderedIds: List<Long>) {
        database.withTransaction {
            orderedIds.forEachIndexed { order, id ->
                check(dao.setAccountDisplayOrder(id, order) == 1) { "账户不存在" }
            }
        }
    }

    override suspend fun clearTransactions() {
        database.withTransaction { dao.deleteAll() }
    }
}

private fun CategoryEntity.toSetting() = CategorySetting(id, name, displayOrder, hidden)
private fun AccountEntity.toSetting() = AccountSetting(id, name, currency, displayOrder, hidden)

private fun validatedName(value: String, itemLabel: String): String {
    val normalized = value.trim()
    if (normalized.isEmpty()) throw SettingsValidationException("${itemLabel}名称不能为空")
    return normalized
}

private fun ensureUnique(value: String, existing: List<String>, itemLabel: String) {
    if (existing.any { it.equals(value, ignoreCase = true) }) {
        throw SettingsValidationException("${itemLabel}名称已存在")
    }
}

private fun validateCompleteOrder(
    orderedIds: List<Long>,
    existingIds: List<Long>,
    itemLabel: String,
) {
    if (
        orderedIds.size != orderedIds.distinct().size ||
        orderedIds.toSet() != existingIds.toSet()
    ) {
        throw SettingsValidationException("${itemLabel}排序数据无效")
    }
}

private suspend inline fun <T> settingsMutation(
    constraintMessage: String,
    block: () -> T,
): T = try {
    block()
} catch (_: SQLiteConstraintException) {
    throw SettingsValidationException(constraintMessage)
} catch (failure: CancellationException) {
    throw failure
}

internal fun sanitizeBankPackages(packages: Set<String>): Set<String> = packages
    .asSequence()
    .map(String::trim)
    .filter(BANK_PACKAGE_NAME::matches)
    .toCollection(linkedSetOf())
    .toSet()

internal fun isValidBankPackage(value: String): Boolean = BANK_PACKAGE_NAME.matches(value.trim())

internal fun recoverPreferenceReads(source: Flow<Preferences>): Flow<Preferences> =
    source.catch { failure ->
        if (failure is IOException) {
            emit(emptyPreferences())
        } else {
            throw failure
        }
    }

private val BANK_PACKAGE_NAME =
    Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
