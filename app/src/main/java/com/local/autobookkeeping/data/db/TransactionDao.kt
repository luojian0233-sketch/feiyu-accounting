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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(row: CategoryEntity): Long

    @Query("SELECT * FROM accounts WHERE name = :name LIMIT 1")
    suspend fun findAccountByName(name: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAccount(row: AccountEntity): Long

    @Transaction
    suspend fun replaceAllInTransaction(rows: List<TransactionEntity>) {
        deleteAll()
        insertAll(rows)
    }
}
