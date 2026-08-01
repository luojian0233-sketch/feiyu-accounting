package com.local.autobookkeeping.backup

import com.local.autobookkeeping.data.SettingsOperations
import com.local.autobookkeeping.data.db.AccountEntity
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.data.db.CategoryEntity
import com.local.autobookkeeping.data.db.MerchantRuleEntity
import com.local.autobookkeeping.data.db.RecognitionReceiptEntity
import com.local.autobookkeeping.data.db.TransactionEntity
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.ui.theme.ThemeStyle
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import kotlin.coroutines.coroutineContext

interface BackupOperations {
    suspend fun export(output: OutputStream)
    suspend fun restore(input: InputStream): RestoreResult
    suspend fun restore(openInput: () -> InputStream?): RestoreResult? =
        openInput()?.use { input -> restore(input) }
}

interface BackupStore {
    suspend fun read(): BackupData
    suspend fun replaceRoomData(data: BackupData)
    suspend fun replaceRoomData(
        data: BackupData,
        onCommitted: () -> Unit,
    ) {
        replaceRoomData(data)
        onCommitted()
    }
    suspend fun replaceTheme(theme: String)
}

sealed interface RestoreResult {
    data class Success(val transactionCount: Int) : RestoreResult
    data class Invalid(val reason: String) : RestoreResult
    data class Failed(
        val reason: String,
        val cause: Throwable? = null,
    ) : RestoreResult
    data class RollbackFailed(val cause: Throwable) : RestoreResult
}

class BackupManager(
    private val store: BackupStore,
    private val codec: BackupJsonCodec = BackupJsonCodec(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BackupOperations {
    private val operationMutex = Mutex()

    private class RestoreAttempt {
        var safety: BackupData? = null
        var roomCommitted = false
        var compensationAttempted = false
        var completedResult: RestoreResult? = null
        var operationFailure: Throwable? = null
    }

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
                    recognitionReceipts = data.recognitionReceipts,
                    theme = data.theme,
                ),
                output,
            )
        }
    }

    override suspend fun restore(input: InputStream): RestoreResult = operationMutex.withLock {
        val attempt = RestoreAttempt()
        withCancellationCompensation(attempt) {
            withContext(ioDispatcher) {
                restoreInCurrentContext(input, attempt)
            }
        }
    }

    override suspend fun restore(openInput: () -> InputStream?): RestoreResult? =
        operationMutex.withLock {
            val attempt = RestoreAttempt()
            withCancellationCompensation(attempt) {
                withContext(ioDispatcher) {
                    openInput()?.let { input -> restoreAndClose(input, attempt) }
                }
            }
        }

    private suspend fun restoreAndClose(
        input: InputStream,
        attempt: RestoreAttempt,
    ): RestoreResult {
        val result = try {
            restoreInCurrentContext(input, attempt)
        } catch (failure: Throwable) {
            try {
                input.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        return try {
            input.close()
            result
        } catch (closeFailure: Throwable) {
            if (closeFailure is CancellationException) throw closeFailure
            when (result) {
                is RestoreResult.Failed -> {
                    val operationFailure = attempt.operationFailure
                    if (operationFailure == null) {
                        result
                    } else {
                        operationFailure.addSuppressed(closeFailure)
                        result.copy(cause = operationFailure)
                    }
                }
                is RestoreResult.RollbackFailed -> {
                    result.cause.addSuppressed(closeFailure)
                    result
                }
                is RestoreResult.Invalid,
                is RestoreResult.Success,
                -> result
            }
        }
    }

    private suspend fun restoreInCurrentContext(
        input: InputStream,
        attempt: RestoreAttempt,
    ): RestoreResult {
        val document = try {
            codec.decode(input).also(::validate)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: BackupFormatException) {
            return RestoreResult.Invalid(INVALID_BACKUP_REASON)
        } catch (failure: Throwable) {
            attempt.operationFailure = failure
            return RestoreResult.Failed(RESTORE_FAILURE_REASON)
        }
        val replacement = document.toData()
        attempt.safety = try {
            store.read()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            attempt.operationFailure = failure
            return RestoreResult.Failed(RESTORE_FAILURE_REASON)
        }
        try {
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                store.replaceRoomData(replacement) {
                    attempt.roomCommitted = true
                }
            }
            store.replaceTheme(replacement.theme)
            return RestoreResult.Success(replacement.transactions.size)
        } catch (failure: Throwable) {
            if (!attempt.roomCommitted) {
                if (failure is CancellationException) throw failure
                attempt.operationFailure = failure
                return RestoreResult.Failed(RESTORE_FAILURE_REASON)
            }
            attempt.compensationAttempted = true
            val compensationFailures = compensate(checkNotNull(attempt.safety))
            if (failure is CancellationException) {
                attachCancellationDiagnostics(failure, compensationFailures)
                throw failure
            }
            attempt.operationFailure = failure
            compensationFailures.forEach(failure::addSuppressed)
            val result = if (compensationFailures.isEmpty()) {
                RestoreResult.Failed(RESTORE_FAILURE_REASON)
            } else {
                RestoreResult.RollbackFailed(failure)
            }
            attempt.completedResult = result
            return result
        }
    }

    private suspend fun <T> withCancellationCompensation(
        attempt: RestoreAttempt,
        operation: suspend () -> T,
    ): T {
        try {
            return operation()
        } catch (failure: CancellationException) {
            if (attempt.roomCommitted && !attempt.compensationAttempted) {
                attempt.compensationAttempted = true
                attachCancellationDiagnostics(
                    failure,
                    compensate(checkNotNull(attempt.safety)),
                )
            } else {
                val completedRollback = attempt.completedResult as? RestoreResult.RollbackFailed
                if (completedRollback != null) {
                    attachCancellationDiagnostics(failure, listOf(completedRollback.cause))
                }
            }
            throw failure
        }
    }

    private suspend fun compensate(safety: BackupData): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        withContext(NonCancellable) {
            runCatching { store.replaceRoomData(safety) }
                .exceptionOrNull()
                ?.let(failures::add)
            runCatching { store.replaceTheme(safety.theme) }
                .exceptionOrNull()
                ?.let(failures::add)
        }
        return failures
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun attachCancellationDiagnostics(
        cancellation: CancellationException,
        diagnostics: List<Throwable>,
    ) {
        diagnostics.forEach(cancellation::addSuppressed)
        val job = coroutineContext[Job]
        val jobCancellation = if (job?.isCancelled == true) {
            job.getCancellationException()
        } else {
            null
        }
        if (jobCancellation != null && jobCancellation !== cancellation) {
            diagnostics.forEach(jobCancellation::addSuppressed)
        }
    }

    private companion object {
        const val SUPPORTED_FORMAT_VERSION = 2
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
                recognitionReceipts = dao.allRecognitionReceipts().map(RecognitionReceiptEntity::toBackup),
                theme = theme,
            )
        }
    }

    override suspend fun replaceRoomData(data: BackupData) {
        replaceRoomData(data) {}
    }

    override suspend fun replaceRoomData(
        data: BackupData,
        onCommitted: () -> Unit,
    ) {
        database.withTransaction {
            dao.deleteAll()
            dao.deleteAllMerchantRules()
            dao.deleteAllAccounts()
            dao.deleteAllCategories()
            dao.insertAllCategories(data.categories.map(BackupCategory::toEntity))
            dao.insertAllAccounts(data.accounts.map(BackupAccount::toEntity))
            dao.insertAllMerchantRules(data.merchantRules.map(BackupMerchantRule::toEntity))
            dao.insertAll(data.transactions.map(BackupTransaction::toEntity))
            data.recognitionReceipts.forEach { dao.insertRecognitionReceipt(it.toEntity()) }
        }
        onCommitted()
    }

    override suspend fun replaceTheme(theme: String) {
        settings.setTheme(ThemeStyle.valueOf(theme))
    }
}

private fun validate(document: BackupDocument) {
    requireBackup(document.formatVersion in 1..2)
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
        requireBackup(row.platform.isNotBlank())
        requireBackup(row.eventKind.isNotBlank())
        requireBackup(row.recognitionConfidence in 0..100)
        requireBackup(row.dedupeKey == null || row.dedupeKey.isNotBlank())
        requireBackup(
            row.redactedNotificationDigest == null ||
                row.redactedNotificationDigest.isNotBlank(),
        )
        requireBackup(row.refundOfId == null || row.refundOfId in transactionIds)
        requireBackup(row.refundOfId != row.id)
    }
    requireUniqueNames(document.recognitionReceipts.map(BackupRecognitionReceipt::digest))
    document.recognitionReceipts.forEach { row ->
        requireBackup(row.transactionId in transactionIds)
        requireBackup(row.channel.isNotBlank())
        requireBackup(row.platform.isNotBlank())
        requireBackup(row.eventKind.isNotBlank())
        requireBackup(row.observedAtEpochMillis > 0)
        requireBackup(row.digest.matches(SHA_256_DIGEST))
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
private val SHA_256_DIGEST = Regex("[0-9a-f]{64}")

private fun BackupDocument.toData() = BackupData(
    transactions = transactions,
    categories = categories,
    accounts = accounts,
    merchantRules = merchantRules,
    recognitionReceipts = recognitionReceipts,
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
    platform = platform,
    eventKind = eventKind,
    recognitionConfidence = recognitionConfidence,
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
    platform = platform,
    eventKind = eventKind,
    recognitionConfidence = recognitionConfidence,
)

private fun RecognitionReceiptEntity.toBackup() = BackupRecognitionReceipt(
    transactionId = transactionId,
    channel = channel,
    platform = platform,
    eventKind = eventKind,
    observedAtEpochMillis = observedAtEpochMillis,
    digest = digest,
)

private fun BackupRecognitionReceipt.toEntity() = RecognitionReceiptEntity(
    transactionId = transactionId,
    channel = channel,
    platform = platform,
    eventKind = eventKind,
    observedAtEpochMillis = observedAtEpochMillis,
    digest = digest,
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
