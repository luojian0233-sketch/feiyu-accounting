package com.local.autobookkeeping.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.data.db.MerchantRuleEntity
import com.local.autobookkeeping.data.db.RecognitionReceiptEntity
import com.local.autobookkeeping.data.db.TransactionDao
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import java.util.Locale

data class RecognitionMatchQuery(
    val platform: PaymentPlatform,
    val direction: Direction,
    val amountMinor: Long,
    val eventKind: TransactionEventKind,
    val from: Instant,
    val to: Instant,
)

data class MerchantRuleMatch(
    val merchantPattern: String,
    val category: String,
    val account: String?,
    val priority: Int,
)

class DuplicateRecognitionReceiptException(
    val digest: String,
    cause: Throwable? = null,
) : RuntimeException("Recognition receipt already exists", cause)

interface MerchantRuleOperations {
    suspend fun rememberMerchantRule(merchant: String, category: String, account: String?)
}

interface RecognitionConfirmationOperations {
    suspend fun confirmAndRemember(transaction: Transaction): Long
}

interface RecognitionStore : MerchantRuleOperations, RecognitionConfirmationOperations {
    suspend fun findReceipt(digest: String): RecognitionReceipt?
    suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction>
    suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long
    suspend fun update(transaction: Transaction, receipt: RecognitionReceipt)
    suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt)
    suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch?
    suspend fun isCategoryVisible(name: String): Boolean
    suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction?
}

class RoomRecognitionStore(
    private val database: AppDatabase,
) : RecognitionStore {
    private val dao: TransactionDao = database.transactionDao()

    override suspend fun findReceipt(digest: String): RecognitionReceipt? =
        dao.findRecognitionReceipt(digest)?.toDomain()

    override suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction> =
        dao.findRecognitionMatches(
            platform = query.platform.name,
            direction = query.direction.name,
            amountMinor = query.amountMinor,
            eventKind = query.eventKind.name,
            fromEpochMillis = query.from.toEpochMilli(),
            toEpochMillis = query.to.toEpochMilli(),
        ).map { it.toDomain() }

    override suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long =
        recognitionWrite(receipt.digest) {
            database.withTransaction {
                val now = Instant.now().toEpochMilli()
                val id = dao.insert(
                    transaction.toEntity(
                        categoryId = dao.categoryIdFor(transaction.category),
                        accountId = dao.accountIdFor(transaction.account),
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                        redactedNotificationDigest = null,
                    ),
                )
                dao.insertRecognitionReceipt(receipt.toEntity(id))
                id
            }
        }

    override suspend fun update(transaction: Transaction, receipt: RecognitionReceipt) {
        recognitionWrite(receipt.digest) {
            database.withTransaction {
                updateTransaction(transaction)
                dao.insertRecognitionReceipt(receipt.toEntity(transaction.id))
            }
        }
    }

    override suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt) {
        recognitionWrite(receipt.digest) {
            database.withTransaction {
                requireNotNull(dao.findById(transactionId)) {
                    "Cannot attach a receipt to a transaction that does not exist"
                }
                dao.insertRecognitionReceipt(receipt.toEntity(transactionId))
            }
        }
    }

    override suspend fun rememberMerchantRule(merchant: String, category: String, account: String?) {
        database.withTransaction {
            rememberMerchantRuleInTransaction(merchant, category, account)
        }
    }

    override suspend fun confirmAndRemember(transaction: Transaction): Long = database.withTransaction {
        updateTransaction(transaction)
        if (transaction.merchant.isNotBlank()) {
            rememberMerchantRuleInTransaction(transaction.merchant, transaction.category, transaction.account)
        }
        transaction.id
    }

    override suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch? =
        dao.findMerchantRuleRecord(normalizeMerchant(normalizedMerchant))?.let { row ->
            MerchantRuleMatch(
                merchantPattern = row.merchantPattern,
                category = row.categoryName,
                account = row.accountName,
                priority = row.priority,
            )
        }

    override suspend fun isCategoryVisible(name: String): Boolean = dao.isCategoryVisible(name)

    override suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction? {
        val originDirection = when (candidate.direction) {
            Direction.EXPENSE -> Direction.INCOME
            Direction.INCOME, null -> Direction.EXPENSE
        }
        return dao.findRefundOrigin(
            platform = candidate.platform.name,
            direction = originDirection.name,
            amountMinor = candidate.amountMinor,
            normalizedMerchant = normalizeMerchant(candidate.merchant.orEmpty()),
            occurredAtEpochMillis = candidate.occurredAt.toEpochMilli(),
        )?.toDomain()
    }

    private suspend fun updateTransaction(transaction: Transaction) {
        require(transaction.id != 0L) { "Cannot update a transaction without an id" }
        val existing = requireNotNull(dao.findById(transaction.id)) {
            "Cannot update a transaction that does not exist"
        }
        val row = transaction.toEntity(
            categoryId = dao.categoryIdFor(transaction.category),
            accountId = dao.accountIdFor(transaction.account),
            createdAtEpochMillis = existing.createdAtEpochMillis,
            updatedAtEpochMillis = Instant.now().toEpochMilli(),
            redactedNotificationDigest = existing.redactedNotificationDigest,
        )
        check(dao.update(row) == 1) { "Transaction disappeared during update" }
    }

    private suspend fun rememberMerchantRuleInTransaction(
        merchant: String,
        category: String,
        account: String?,
    ) {
        val merchantPattern = normalizeMerchant(merchant)
        if (merchantPattern.isBlank()) return
        val existing = dao.findMerchantRuleByPattern(merchantPattern)
        val row = MerchantRuleEntity(
            id = existing?.id ?: 0,
            merchantPattern = merchantPattern,
            categoryId = dao.categoryIdFor(category),
            accountId = dao.accountIdFor(account.orEmpty()),
            priority = existing?.priority ?: 0,
        )
        if (existing == null) {
            dao.insertMerchantRule(row)
        } else {
            check(dao.updateMerchantRule(row) == 1) { "Merchant rule disappeared during update" }
        }
    }

    private suspend fun <T> recognitionWrite(digest: String, block: suspend () -> T): T = try {
        block()
    } catch (failure: SQLiteConstraintException) {
        if (dao.findRecognitionReceipt(digest) != null) {
            throw DuplicateRecognitionReceiptException(digest, failure)
        }
        throw failure
    }
}

private fun RecognitionReceipt.toEntity(transactionId: Long) = RecognitionReceiptEntity(
    transactionId = transactionId,
    channel = channel.name,
    platform = platform.name,
    eventKind = eventKind.name,
    observedAtEpochMillis = observedAt.toEpochMilli(),
    digest = digest,
)

private fun RecognitionReceiptEntity.toDomain() = RecognitionReceipt(
    transactionId = transactionId,
    channel = CaptureChannel.valueOf(channel),
    platform = PaymentPlatform.valueOf(platform),
    eventKind = TransactionEventKind.valueOf(eventKind),
    observedAt = Instant.ofEpochMilli(observedAtEpochMillis),
    digest = digest,
)

private fun normalizeMerchant(value: String): String = value.trim().lowercase(Locale.ROOT)
