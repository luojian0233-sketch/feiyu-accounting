package com.local.autobookkeeping.backup

import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.data.db.AccountEntity
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.data.db.CategoryEntity
import com.local.autobookkeeping.data.db.MerchantRuleEntity
import com.local.autobookkeeping.data.db.TransactionEntity
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.ui.theme.ThemeStyle
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction

interface BackupOperations {
    suspend fun export(output: OutputStream)
    suspend fun restore(input: InputStream): RestoreResult
}

interface BackupStore {
    suspend fun read(): BackupData
    suspend fun replaceRoomData(data: BackupData)
    suspend fun replaceTheme(theme: String)
}

sealed interface RestoreResult {
    data class Success(val transactionCount: Int) : RestoreResult
    data class Invalid(val reason: String) : RestoreResult
    data class Failed(val reason: String) : RestoreResult
}

class BackupManager(
    private val store: BackupStore,
    private val codec: BackupJsonCodec = BackupJsonCodec(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupOperations {
    private val operationMutex = Mutex()

    override suspend fun export(output: OutputStream) = operationMutex.withLock {
        withContext(ioDispatcher) {
            val data = store.read()
            codec.encode(
                BackupDocument(
                    formatVersion = SUPPORTED_FORMAT_VERSION,
                    exportedAtEpochMillis = currentTimeMillis(),
                    transactions = data.transactions,
                    categories = data.categories,
                    accounts = data.accounts,
                    merchantRules = data.merchantRules,
                    theme = data.theme,
                ),
                output,
            )
        }
    }

    override suspend fun restore(input: InputStream): RestoreResult = operationMutex.withLock {
        withContext(ioDispatcher) {
            val document = try {
                codec.decode(input).also(::validate)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: BackupFormatException) {
                return@withContext RestoreResult.Invalid(INVALID_BACKUP_REASON)
            } catch (_: Throwable) {
                return@withContext RestoreResult.Failed(RESTORE_FAILURE_REASON)
            }
            val replacement = document.toData()
            val safety = try {
                store.read()
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                return@withContext RestoreResult.Failed(RESTORE_FAILURE_REASON)
            }
            try {
                store.replaceRoomData(replacement)
                store.replaceTheme(replacement.theme)
                RestoreResult.Success(replacement.transactions.size)
            } catch (failure: Throwable) {
                compensate(safety, failure)
                if (failure is CancellationException) throw failure
                RestoreResult.Failed(RESTORE_FAILURE_REASON)
            }
        }
    }

    private suspend fun compensate(safety: BackupData, originalFailure: Throwable) {
        withContext(NonCancellable) {
            runCatching { store.replaceRoomData(safety) }
                .exceptionOrNull()
                ?.let(originalFailure::addSuppressed)
            runCatching { store.replaceTheme(safety.theme) }
                .exceptionOrNull()
                ?.let(originalFailure::addSuppressed)
        }
    }

    private companion object {
        const val SUPPORTED_FORMAT_VERSION = 1
        const val INVALID_BACKUP_REASON = "备份文件无效，未替换当前数据"
        const val RESTORE_FAILURE_REASON = "恢复失败，当前数据已保留"
    }
}

class AppDatabaseBackupStore(
    private val database: AppDatabase,
    private val settings: SettingsOperations,
) : BackupStore {
    private val dao = database.transactionDao()

    override suspend fun read(): BackupData {
        val theme = settings.theme.first().name
        return database.withTransaction {
            BackupData(
                transactions = dao.allTransactions().map(TransactionEntity::toBackup),
                categories = dao.allCategories().map(CategoryEntity::toBackup),
                accounts = dao.allAccounts().map(AccountEntity::toBackup),
                merchantRules = dao.allMerchantRules().map(MerchantRuleEntity::toBackup),
                theme = theme,
            )
        }
    }

    override suspend fun replaceRoomData(data: BackupData) {
        database.withTransaction {
            dao.deleteAll()
            dao.deleteAllMerchantRules()
            dao.deleteAllAccounts()
            dao.deleteAllCategories()
            dao.insertAllCategories(data.categories.map(BackupCategory::toEntity))
            dao.insertAllAccounts(data.accounts.map(BackupAccount::toEntity))
            dao.insertAllMerchantRules(data.merchantRules.map(BackupMerchantRule::toEntity))
            dao.insertAll(data.transactions.map(BackupTransaction::toEntity))
        }
    }

    override suspend fun replaceTheme(theme: String) {
        settings.setTheme(ThemeStyle.valueOf(theme))
    }
}

private fun validate(document: BackupDocument) {
    requireBackup(document.formatVersion == 1)
    requireBackup(document.exportedAtEpochMillis > 0)
    requireBackup(ThemeStyle.entries.any { it.name == document.theme })

    requireUniquePositiveIds(document.categories.map(BackupCategory::id))
    requireUniquePositiveIds(document.accounts.map(BackupAccount::id))
    requireUniquePositiveIds(document.merchantRules.map(BackupMerchantRule::id))
    requireUniquePositiveIds(document.transactions.map(BackupTransaction::id))
    requireUniqueNames(document.categories.map(BackupCategory::name))
    requireUniqueNames(document.accounts.map(BackupAccount::name))
    requireUniqueNames(document.merchantRules.map(BackupMerchantRule::merchantPattern))
    requireUniqueNames(document.transactions.mapNotNull(BackupTransaction::dedupeKey))

    document.categories.forEach { row ->
        requireBackup(row.name.isNotBlank())
        requireBackup(row.displayOrder >= 0)
    }
    document.accounts.forEach { row ->
        requireBackup(row.name.isNotBlank())
        requireBackup(row.currency.isCurrency())
        requireBackup(row.displayOrder >= 0)
    }

    val categoryIds = document.categories.mapTo(hashSetOf(), BackupCategory::id)
    val accountIds = document.accounts.mapTo(hashSetOf(), BackupAccount::id)
    val transactionIds = document.transactions.mapTo(hashSetOf(), BackupTransaction::id)

    document.merchantRules.forEach { row ->
        requireBackup(row.merchantPattern.isNotBlank())
        requireBackup(row.categoryId in categoryIds)
        requireBackup(row.accountId == null || row.accountId in accountIds)
    }
    document.transactions.forEach { row ->
        requireBackup(row.amountMinor > 0)
        requireBackup(row.currency.isCurrency())
        requireBackup(Direction.entries.any { it.name == row.direction })
        requireBackup(Source.entries.any { it.name == row.source })
        requireBackup(row.categoryId in categoryIds)
        requireBackup(row.accountId == null || row.accountId in accountIds)
        requireBackup(row.occurredAtEpochMillis > 0)
        requireBackup(row.createdAtEpochMillis > 0)
        requireBackup(row.updatedAtEpochMillis >= row.createdAtEpochMillis)
        requireBackup(row.dedupeKey == null || row.dedupeKey.isNotBlank())
        requireBackup(
            row.redactedNotificationDigest == null ||
                row.redactedNotificationDigest.isNotBlank(),
        )
        requireBackup(row.refundOfId == null || row.refundOfId in transactionIds)
        requireBackup(row.refundOfId != row.id)
    }
}

private fun requireUniquePositiveIds(ids: List<Long>) {
    requireBackup(ids.all { it > 0 })
    requireBackup(ids.size == ids.toSet().size)
}

private fun requireUniqueNames(values: List<String>) {
    requireBackup(values.size == values.map { it.trim().lowercase() }.toSet().size)
}

private fun requireBackup(condition: Boolean) {
    if (!condition) throw BackupFormatException("备份内容校验失败")
}

private fun String.isCurrency(): Boolean = matches(Regex("[A-Z]{3}"))

private fun BackupDocument.toData() = BackupData(
    transactions = transactions,
    categories = categories,
    accounts = accounts,
    merchantRules = merchantRules,
    theme = theme,
)

private fun TransactionEntity.toBackup() = BackupTransaction(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    direction = direction,
    categoryId = categoryId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    merchant = merchant,
    note = note,
    accountId = accountId,
    source = source,
    pendingConfirmation = pendingConfirmation,
    internalTransfer = internalTransfer,
    dedupeKey = dedupeKey,
    redactedNotificationDigest = redactedNotificationDigest,
    refundOfId = refundOfId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun BackupTransaction.toEntity() = TransactionEntity(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    direction = direction,
    categoryId = categoryId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    merchant = merchant,
    note = note,
    accountId = accountId,
    source = source,
    pendingConfirmation = pendingConfirmation,
    internalTransfer = internalTransfer,
    dedupeKey = dedupeKey,
    redactedNotificationDigest = redactedNotificationDigest,
    refundOfId = refundOfId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun CategoryEntity.toBackup() = BackupCategory(id, name, displayOrder, hidden)
private fun BackupCategory.toEntity() = CategoryEntity(id, name, displayOrder, hidden)
private fun AccountEntity.toBackup() = BackupAccount(id, name, currency, displayOrder, hidden)
private fun BackupAccount.toEntity() = AccountEntity(id, name, currency, displayOrder, hidden)

private fun MerchantRuleEntity.toBackup() = BackupMerchantRule(
    id = id,
    merchantPattern = merchantPattern,
    categoryId = categoryId,
    accountId = accountId,
    priority = priority,
)

private fun BackupMerchantRule.toEntity() = MerchantRuleEntity(
    id = id,
    merchantPattern = merchantPattern,
    categoryId = categoryId,
    accountId = accountId,
    priority = priority,
)
