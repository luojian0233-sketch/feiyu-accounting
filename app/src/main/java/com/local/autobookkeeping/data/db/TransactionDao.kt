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

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TransactionEntity?

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

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findCategoryByName(name: String): CategoryEntity?

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
