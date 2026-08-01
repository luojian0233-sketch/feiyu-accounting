package com.local.autobookkeeping.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @Test
    fun migrationNormalizesGappedIdsAndNewRowsAppendAfterMaximum() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "task8-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)",
            )
            sqlite.execSQL(
                "CREATE UNIQUE INDEX index_categories_name ON categories(name)",
            )
            sqlite.execSQL(
                "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, currency TEXT NOT NULL)",
            )
            sqlite.execSQL(
                "CREATE UNIQUE INDEX index_accounts_name ON accounts(name)",
            )
            sqlite.execSQL(
                """
                CREATE TABLE transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amountMinor INTEGER NOT NULL,
                    currency TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    occurredAtEpochMillis INTEGER NOT NULL,
                    merchant TEXT NOT NULL,
                    note TEXT NOT NULL,
                    accountId INTEGER,
                    source TEXT NOT NULL,
                    pendingConfirmation INTEGER NOT NULL,
                    internalTransfer INTEGER NOT NULL,
                    dedupeKey TEXT,
                    redactedNotificationDigest TEXT,
                    refundOfId INTEGER,
                    createdAtEpochMillis INTEGER NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL("CREATE UNIQUE INDEX index_transactions_dedupeKey ON transactions(dedupeKey)")
            sqlite.execSQL("CREATE INDEX index_transactions_categoryId ON transactions(categoryId)")
            sqlite.execSQL("CREATE INDEX index_transactions_accountId ON transactions(accountId)")
            sqlite.execSQL(
                """
                CREATE TABLE merchant_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    merchantPattern TEXT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    accountId INTEGER,
                    priority INTEGER NOT NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL("CREATE UNIQUE INDEX index_merchant_rules_merchantPattern ON merchant_rules(merchantPattern)")
            sqlite.execSQL("CREATE INDEX index_merchant_rules_categoryId ON merchant_rules(categoryId)")
            sqlite.execSQL("CREATE INDEX index_merchant_rules_accountId ON merchant_rules(accountId)")
            sqlite.execSQL("INSERT INTO categories(id, name) VALUES (2, 'A'), (7, 'B'), (19, 'C')")
            sqlite.execSQL("INSERT INTO accounts(id, name, currency) VALUES (3, 'Cash', 'CNY'), (11, 'Bank', 'CNY')")
            sqlite.version = 1
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        try {
            assertEquals(listOf(0, 1, 2), db.transactionDao().observeCategories().first().map { it.displayOrder })
            assertEquals(listOf(0, 1), db.transactionDao().observeAccounts().first().map { it.displayOrder })
            val settings = SettingsRepository(context, db)
            settings.addCategory("D")
            settings.addAccount("Wallet")
            assertEquals(listOf(0, 1, 2, 3), db.transactionDao().observeCategories().first().map { it.displayOrder })
            assertEquals(listOf(0, 1, 2), db.transactionDao().observeAccounts().first().map { it.displayOrder })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migration2To3PreservesRowsAndAddsRecognitionDefaults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "recognition-migration.db"
        createVersion2Database(context, name, transactionId = 7)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        try {
            val row = db.transactionDao().findById(7)!!
            assertEquals("UNKNOWN", row.platform)
            assertEquals("UNKNOWN", row.eventKind)
            assertEquals(100, row.recognitionConfidence)
            assertTrue(db.transactionDao().allRecognitionReceipts().isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersion2Database(context: Context, name: String, transactionId: Long) {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, displayOrder INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0)",
            )
            sqlite.execSQL("CREATE UNIQUE INDEX index_categories_name ON categories(name)")
            sqlite.execSQL(
                "CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, currency TEXT NOT NULL, displayOrder INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0)",
            )
            sqlite.execSQL("CREATE UNIQUE INDEX index_accounts_name ON accounts(name)")
            sqlite.execSQL(
                """
                CREATE TABLE transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    amountMinor INTEGER NOT NULL,
                    currency TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    occurredAtEpochMillis INTEGER NOT NULL,
                    merchant TEXT NOT NULL,
                    note TEXT NOT NULL,
                    accountId INTEGER,
                    source TEXT NOT NULL,
                    pendingConfirmation INTEGER NOT NULL,
                    internalTransfer INTEGER NOT NULL,
                    dedupeKey TEXT,
                    redactedNotificationDigest TEXT,
                    refundOfId INTEGER,
                    createdAtEpochMillis INTEGER NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL("CREATE UNIQUE INDEX index_transactions_dedupeKey ON transactions(dedupeKey)")
            sqlite.execSQL("CREATE INDEX index_transactions_categoryId ON transactions(categoryId)")
            sqlite.execSQL("CREATE INDEX index_transactions_accountId ON transactions(accountId)")
            sqlite.execSQL(
                """
                CREATE TABLE merchant_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    merchantPattern TEXT NOT NULL,
                    categoryId INTEGER NOT NULL,
                    accountId INTEGER,
                    priority INTEGER NOT NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            sqlite.execSQL("CREATE UNIQUE INDEX index_merchant_rules_merchantPattern ON merchant_rules(merchantPattern)")
            sqlite.execSQL("CREATE INDEX index_merchant_rules_categoryId ON merchant_rules(categoryId)")
            sqlite.execSQL("CREATE INDEX index_merchant_rules_accountId ON merchant_rules(accountId)")
            sqlite.execSQL("INSERT INTO categories(id, name) VALUES (1, 'Food')")
            sqlite.execSQL("INSERT INTO accounts(id, name, currency) VALUES (1, 'Cash', 'CNY')")
            sqlite.execSQL(
                """
                INSERT INTO transactions(
                    id, amountMinor, currency, direction, categoryId, occurredAtEpochMillis,
                    merchant, note, accountId, source, pendingConfirmation, internalTransfer,
                    dedupeKey, redactedNotificationDigest, refundOfId, createdAtEpochMillis,
                    updatedAtEpochMillis
                ) VALUES (?, 2800, 'CNY', 'EXPENSE', 1, 1785011400000, 'Coffee shop', '', 1,
                    'MANUAL', 0, 0, NULL, NULL, NULL, 1785011400000, 1785011400000)
                """.trimIndent(),
                arrayOf(transactionId),
            )
            sqlite.version = 2
        }
    }
}
