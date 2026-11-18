package com.local.autobookkeeping.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.local.autobookkeeping.data.db.AccountEntity
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.data.db.CategoryEntity
import com.local.autobookkeeping.data.db.TransactionDao
import com.local.autobookkeeping.data.db.TransactionEntity
import com.local.autobookkeeping.data.db.TransactionRecord
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface LedgerRepository {
    fun observeTransactions(): Flow<List<Transaction>>
    suspend fun save(transaction: Transaction): Long
    suspend fun delete(id: Long)
    suspend fun deleteAll(ids: List<Long>) {
        ids.forEach { delete(it) }
    }
    suspend fun findById(id: Long): Transaction? = observeTransactions().first().firstOrNull { it.id == id }
    suspend fun findByDedupeKey(key: String): Transaction?
    suspend fun replaceAllInTransaction(transactions: List<Transaction>)
}

class DuplicateDedupeKeyException(
    val dedupeKey: String,
    cause: Throwable? = null,
) : RuntimeException("A transaction with this dedupe key already exists", cause)

class RoomLedgerRepository(
    private val database: AppDatabase,
) : LedgerRepository {
    private val dao: TransactionDao = database.transactionDao()

    override fun observeTransactions(): Flow<List<Transaction>> = dao.observeAllRecords()
        .map { records -> records.map(TransactionRecord::toDomain) }

    override suspend fun save(transaction: Transaction): Long {
        return try {
            database.withTransaction {
                val now = Instant.now().toEpochMilli()
                val existing = if (transaction.id == 0L) null else dao.findById(transaction.id)
                require(transaction.id == 0L || existing != null) {
                    "Cannot update a transaction that does not exist"
                }

                val row = transaction.toEntity(
                    categoryId = dao.categoryIdFor(transaction.category),
                    accountId = dao.accountIdFor(transaction.account),
                    createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = now,
                    redactedNotificationDigest = existing?.redactedNotificationDigest,
                )
                if (existing == null) dao.insert(row) else {
                    check(dao.update(row) == 1) { "Transaction disappeared during update" }
                    row.id
                }
            }
        } catch (failure: SQLiteConstraintException) {
            val dedupeKey = transaction.dedupeKey
            if (dedupeKey != null && dao.findByDedupeKey(dedupeKey) != null) {
                throw DuplicateDedupeKeyException(dedupeKey, failure)
            }
            throw failure
        }
    }

    override suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    override suspend fun findById(id: Long): Transaction? = dao.findRecordById(id)?.toDomain()

    override suspend fun findByDedupeKey(key: String): Transaction? = dao.findRecordByDedupeKey(key)?.toDomain()

    override suspend fun replaceAllInTransaction(transactions: List<Transaction>) {
        database.withTransaction {
            val now = Instant.now().toEpochMilli()
            val rows = transactions.map { transaction ->
                transaction.toEntity(
                    categoryId = dao.categoryIdFor(transaction.category),
                    accountId = dao.accountIdFor(transaction.account),
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    redactedNotificationDigest = null,
                )
            }
            dao.replaceAllInTransaction(rows)
        }
    }
}

internal suspend fun TransactionDao.categoryIdFor(name: String): Long {
    findCategoryByName(name)?.let { return it.id }
    insertCategory(CategoryEntity(name = name))
    return checkNotNull(findCategoryByName(name)) { "Category insert did not persist" }.id
}

internal suspend fun TransactionDao.accountIdFor(name: String): Long? {
    if (name.isBlank()) return null
    findAccountByName(name)?.let { return it.id }
    insertAccount(AccountEntity(name = name))
    return checkNotNull(findAccountByName(name)) { "Account insert did not persist" }.id
}

internal fun Transaction.toEntity(
    categoryId: Long,
    accountId: Long?,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
    redactedNotificationDigest: String?,
) = TransactionEntity(
    id = id,
    amountMinor = amountMinor,
    currency = currency,
    direction = direction.name,
    categoryId = categoryId,
    occurredAtEpochMillis = occurredAt.toEpochMilli(),
    merchant = merchant,
    note = note,
    accountId = accountId,
    source = source.name,
    pendingConfirmation = pendingConfirmation,
    internalTransfer = internalTransfer,
    dedupeKey = dedupeKey,
    redactedNotificationDigest = redactedNotificationDigest,
    refundOfId = refundOfId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    platform = platform.name,
    eventKind = eventKind.name,
    recognitionConfidence = recognitionConfidence,
)

internal fun TransactionRecord.toDomain() = Transaction(
    id = transaction.id,
    amountMinor = transaction.amountMinor,
    currency = transaction.currency,
    direction = Direction.valueOf(transaction.direction),
    category = categoryName,
    occurredAt = Instant.ofEpochMilli(transaction.occurredAtEpochMillis),
    merchant = transaction.merchant,
    note = transaction.note,
    account = accountName.orEmpty(),
    source = Source.valueOf(transaction.source),
    pendingConfirmation = transaction.pendingConfirmation,
    internalTransfer = transaction.internalTransfer,
    dedupeKey = transaction.dedupeKey,
    refundOfId = transaction.refundOfId,
    platform = PaymentPlatform.valueOf(transaction.platform),
    eventKind = TransactionEventKind.valueOf(transaction.eventKind),
    recognitionConfidence = transaction.recognitionConfidence,
)
