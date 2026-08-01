package com.local.autobookkeeping.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        MerchantRuleEntity::class,
        RecognitionReceiptEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val DATABASE_NAME = "autobookkeeping.db"

        private val EXPENSE_BUILT_IN_CATEGORIES = listOf(
            "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果", "服饰", "娱乐", "美容", "通讯",
            "医疗", "学习", "游戏", "住房", "礼品", "宠物", "汽车", "数码", "办公", "运动", "捐赠", "彩票",
            "红包", "礼金", "转账", "其他",
        )

        private val INCOME_BUILT_IN_CATEGORIES = listOf(
            "红包", "工资", "奖金", "理财", "分红", "礼金", "退款", "转账", "其他",
        )

        private val BUILT_IN_CATEGORIES = (EXPENSE_BUILT_IN_CATEGORIES + INCOME_BUILT_IN_CATEGORIES).distinct()

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).addCallback(CategorySeedCallback).build()

        fun categorySeedCallback(): Callback = CategorySeedCallback

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE categories ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN displayOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                normalizeTableDisplayOrder(db, "categories")
                normalizeTableDisplayOrder(db, "accounts")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN platform TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN eventKind TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN recognitionConfidence INTEGER NOT NULL DEFAULT 100")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recognition_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        transactionId INTEGER NOT NULL,
                        channel TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        eventKind TEXT NOT NULL,
                        observedAtEpochMillis INTEGER NOT NULL,
                        digest TEXT NOT NULL,
                        FOREIGN KEY(transactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recognition_receipts_transactionId ON recognition_receipts(transactionId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recognition_receipts_digest ON recognition_receipts(digest)")
            }
        }
    }

    private object CategorySeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            BUILT_IN_CATEGORIES.forEachIndexed { index, name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (name, displayOrder, hidden) VALUES (?, ?, 0)",
                    arrayOf<Any>(name, index),
                )
            }
        }
    }
}

private fun normalizeTableDisplayOrder(
    db: SupportSQLiteDatabase,
    table: String,
) {
    val ids = buildList {
        db.query("SELECT id FROM $table ORDER BY id ASC").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getLong(0))
        }
    }
    normalizedDisplayOrders(ids).forEach { (id, order) ->
        db.execSQL("UPDATE $table SET displayOrder = ? WHERE id = ?", arrayOf<Any>(order, id))
    }
}

internal fun normalizedDisplayOrders(ids: List<Long>): Map<Long, Int> =
    ids.distinct().sorted().mapIndexed { order, id -> id to order }.toMap()

internal fun nextDisplayOrder(existingOrders: List<Int>): Int =
    existingOrders.maxOrNull()?.plus(1) ?: 0
