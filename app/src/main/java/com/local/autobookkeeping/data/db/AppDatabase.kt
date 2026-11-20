package com.local.autobookkeeping.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.local.autobookkeeping.domain.model.BUILT_IN_CATEGORY_NAMES
import com.local.autobookkeeping.domain.model.BUILT_IN_CATEGORY_NAMES_ADDED_AFTER_SEED
import com.local.autobookkeeping.domain.model.CATEGORY_UNSURE
import com.local.autobookkeeping.domain.model.LEGACY_PENDING_CATEGORY

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
            BUILT_IN_CATEGORY_NAMES.forEachIndexed { index, name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (name, displayOrder, hidden) VALUES (?, ?, 0)",
                    arrayOf<Any>(name, index),
                )
            }
        }

        /**
         * 「待确认」改名「待分类」：语义从「整笔不算数、不进统计」变成「已入账、分类待补」。
         *
         * 老库里挂在这个分类下的记录跟着换过去，否则升级之后它们仍然挡着统计。
         * 三步都幂等，每次打开跑一遍没有副作用。
         */
        override fun onOpen(db: SupportSQLiteDatabase) {
            // 新增的内置分类只补真正缺的，用户手动删掉的会在下次启动复活 —— 数量极少，可接受。
            BUILT_IN_CATEGORY_NAMES_ADDED_AFTER_SEED.forEach { name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories (name, displayOrder, hidden) " +
                        "VALUES (?, (SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM categories), 0)",
                    arrayOf<Any>(name),
                )
            }
            db.execSQL(
                "INSERT OR IGNORE INTO categories (name, displayOrder, hidden) " +
                    "VALUES (?, (SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM categories), 0)",
                arrayOf<Any>(CATEGORY_UNSURE),
            )
            db.execSQL(
                "UPDATE transactions SET categoryId = (SELECT id FROM categories WHERE name = ?) " +
                    "WHERE categoryId IN (SELECT id FROM categories WHERE name = ?)",
                arrayOf<Any>(CATEGORY_UNSURE, LEGACY_PENDING_CATEGORY),
            )
            db.execSQL(
                "DELETE FROM categories WHERE name = ?",
                arrayOf<Any>(LEGACY_PENDING_CATEGORY),
            )
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
