package com.local.autobookkeeping.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class TransactionRecord(
    @Embedded val transaction: TransactionEntity,
    val categoryName: String,
    val accountName: String?,
)

data class MerchantRuleRecord(
    val merchantPattern: String,
    val categoryName: String,
    val accountName: String?,
    val priority: Int,
)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT transactions.*, categories.name AS categoryName, accounts.name AS accountName
        FROM transactions
        INNER JOIN categories ON categories.id = transactions.categoryId
        LEFT JOIN accounts ON accounts.id = transactions.accountId
        ORDER BY transactions.occurredAtEpochMillis DESC
        """,
    )
    fun observeAllRecords(): Flow<List<TransactionRecord>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<TransactionEntity>)

    @Update
    suspend fun update(row: TransactionEntity): Int

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("DELETE FROM merchant_rules")
    suspend fun deleteAllMerchantRules()

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    @Query("SELECT * FROM transactions ORDER BY id ASC")
    suspend fun allTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM categories ORDER BY id ASC")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT * FROM accounts ORDER BY id ASC")
    suspend fun allAccounts(): List<AccountEntity>

    @Query("SELECT * FROM merchant_rules ORDER BY id ASC")
    suspend fun allMerchantRules(): List<MerchantRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllCategories(rows: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllAccounts(rows: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllMerchantRules(rows: List<MerchantRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMerchantRule(row: MerchantRuleEntity): Long

    @Update
    suspend fun updateMerchantRule(row: MerchantRuleEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecognitionReceipt(row: RecognitionReceiptEntity): Long

    @Query("SELECT * FROM recognition_receipts WHERE digest = :digest LIMIT 1")
    suspend fun findRecognitionReceipt(digest: String): RecognitionReceiptEntity?

    @Query("SELECT * FROM recognition_receipts ORDER BY id ASC")
    suspend fun allRecognitionReceipts(): List<RecognitionReceiptEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TransactionEntity?

    @Query(
        """
        SELECT transactions.*, categories.name AS categoryName, accounts.name AS accountName
        FROM transactions
        INNER JOIN categories ON categories.id = transactions.categoryId
        LEFT JOIN accounts ON accounts.id = transactions.accountId
        WHERE transactions.id = :id
        LIMIT 1
        """,
    )
    suspend fun findRecordById(id: Long): TransactionRecord?

    @Query("SELECT * FROM transactions WHERE dedupeKey = :key LIMIT 1")
    suspend fun findByDedupeKey(key: String): TransactionEntity?

    @Query(
        """
        SELECT transactions.*, categories.name AS categoryName, accounts.name AS accountName
        FROM transactions
        INNER JOIN categories ON categories.id = transactions.categoryId
        LEFT JOIN accounts ON accounts.id = transactions.accountId
        WHERE transactions.dedupeKey = :key
        LIMIT 1
        """,
    )
    suspend fun findRecordByDedupeKey(key: String): TransactionRecord?

    @Query(
        """
        SELECT transactions.*, categories.name AS categoryName, accounts.name AS accountName
        FROM transactions
        INNER JOIN categories ON categories.id = transactions.categoryId
        LEFT JOIN accounts ON accounts.id = transactions.accountId
        WHERE transactions.platform = :platform
          AND transactions.direction = :direction
          AND transactions.amountMinor = :amountMinor
          AND transactions.eventKind = :eventKind
          AND transactions.occurredAtEpochMillis BETWEEN :fromEpochMillis AND :toEpochMillis
        ORDER BY transactions.occurredAtEpochMillis DESC, transactions.id DESC
        """,
    )
    suspend fun findRecognitionMatches(
        platform: String,
        direction: String,
        amountMinor: Long,
        eventKind: String,
        fromEpochMillis: Long,
        toEpochMillis: Long,
    ): List<TransactionRecord>

    @Query(
        """
        SELECT transactions.*, categories.name AS categoryName, accounts.name AS accountName
        FROM transactions
        INNER JOIN categories ON categories.id = transactions.categoryId
        LEFT JOIN accounts ON accounts.id = transactions.accountId
        WHERE transactions.platform = :platform
          AND transactions.direction = :direction
          AND transactions.amountMinor = :amountMinor
          AND transactions.eventKind != 'REFUND'
          AND transactions.occurredAtEpochMillis <= :occurredAtEpochMillis
          AND (:normalizedMerchant = '' OR LOWER(TRIM(transactions.merchant)) = :normalizedMerchant)
        ORDER BY transactions.occurredAtEpochMillis DESC, transactions.id DESC
        LIMIT 2
        """,
    )
    suspend fun findRefundOrigin(
        platform: String,
        direction: String,
        amountMinor: Long,
        normalizedMerchant: String,
        occurredAtEpochMillis: Long,
    ): List<TransactionRecord>

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findCategoryByName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) > 0 FROM categories WHERE name = :name AND hidden = 0")
    suspend fun isCategoryVisible(name: String): Boolean

    @Query("SELECT * FROM merchant_rules WHERE merchantPattern = :merchantPattern LIMIT 1")
    suspend fun findMerchantRuleByPattern(merchantPattern: String): MerchantRuleEntity?

    @Query(
        """
        SELECT merchant_rules.merchantPattern,
               categories.name AS categoryName,
               accounts.name AS accountName,
               merchant_rules.priority
        FROM merchant_rules
        INNER JOIN categories ON categories.id = merchant_rules.categoryId
        LEFT JOIN accounts ON accounts.id = merchant_rules.accountId
        WHERE merchant_rules.merchantPattern = :merchantPattern
        LIMIT 1
        """,
    )
    suspend fun findMerchantRuleRecord(merchantPattern: String): MerchantRuleRecord?

    @Query("SELECT name FROM categories ORDER BY name ASC")
    suspend fun categoryNames(): List<String>

    @Query("SELECT * FROM categories ORDER BY displayOrder ASC, id ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(row: CategoryEntity): Long

    @Query("SELECT displayOrder FROM categories")
    suspend fun categoryDisplayOrders(): List<Int>

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun renameCategory(id: Long, name: String): Int

    @Query("UPDATE categories SET hidden = :hidden WHERE id = :id")
    suspend fun setCategoryHidden(id: Long, hidden: Boolean): Int

    @Query("UPDATE categories SET displayOrder = :displayOrder WHERE id = :id")
    suspend fun setCategoryDisplayOrder(id: Long, displayOrder: Int): Int

    @Query("SELECT * FROM accounts WHERE name = :name LIMIT 1")
    suspend fun findAccountByName(name: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY displayOrder ASC, id ASC")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(row: AccountEntity): Long

    @Query("SELECT displayOrder FROM accounts")
    suspend fun accountDisplayOrders(): List<Int>

    @Query("UPDATE accounts SET name = :name WHERE id = :id")
    suspend fun renameAccount(id: Long, name: String): Int

    @Query("UPDATE accounts SET hidden = :hidden WHERE id = :id")
    suspend fun setAccountHidden(id: Long, hidden: Boolean): Int

    @Query("UPDATE accounts SET displayOrder = :displayOrder WHERE id = :id")
    suspend fun setAccountDisplayOrder(id: Long, displayOrder: Int): Int

    @Query("SELECT COUNT(*) FROM transactions")
    fun observeTransactionCount(): Flow<Int>

    @Transaction
    suspend fun replaceAllInTransaction(rows: List<TransactionEntity>) {
        deleteAll()
        insertAll(rows)
    }
}
