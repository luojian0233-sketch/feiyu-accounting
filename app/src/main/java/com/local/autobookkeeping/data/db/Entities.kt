package com.local.autobookkeeping.data.db

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "0") val displayOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["name"], unique = true)],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val currency: String = "CNY",
    @ColumnInfo(defaultValue = "0") val displayOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

@Entity(
    tableName = "merchant_rules",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["merchantPattern"], unique = true), Index(value = ["categoryId"]), Index(value = ["accountId"])],
)
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantPattern: String,
    val categoryId: Long,
    val accountId: Long? = null,
    val priority: Int = 0,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["categoryId"]),
        Index(value = ["accountId"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long,
    val currency: String,
    val direction: String,
    val categoryId: Long,
    val occurredAtEpochMillis: Long,
    val merchant: String,
    val note: String,
    val accountId: Long?,
    val source: String,
    val pendingConfirmation: Boolean,
    val internalTransfer: Boolean,
    val dedupeKey: String?,
    val redactedNotificationDigest: String?,
    val refundOfId: Long?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val platform: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val eventKind: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "100") val recognitionConfidence: Int = 100,
)

@Entity(
    tableName = "recognition_receipts",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId"), Index(value = ["digest"], unique = true)],
)
data class RecognitionReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val channel: String,
    val platform: String,
    val eventKind: String,
    val observedAtEpochMillis: Long,
    val digest: String,
)
