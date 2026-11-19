package com.local.autobookkeeping.data.db

import androidx.room.Room
import com.local.autobookkeeping.domain.model.CATEGORY_UNSURE
import com.local.autobookkeeping.domain.model.LEGACY_PENDING_CATEGORY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「待确认」→「待分类」是纯数据改名，靠 [AppDatabase] 的 onOpen 幂等 SQL 完成，
 * 不动版本号。这里模拟一份老库：分类叫「待确认」、交易挂在它下面，
 * 然后正常打开，看名字有没有换掉、记录有没有跟过去、再开一次会不会炸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnsureCategoryMigrationTest {
    @Test
    fun `legacy pending category is renamed and its rows follow`() = runBlocking(Dispatchers.IO) {
        val app = RuntimeEnvironment.getApplication()
        val dbName = "unsure-category-migration.db"
        app.deleteDatabase(dbName)

        val legacy = Room.databaseBuilder(app, AppDatabase::class.java, dbName).build()
        try {
            val dao = legacy.transactionDao()
            dao.insertAllCategories(listOf(CategoryEntity(name = LEGACY_PENDING_CATEGORY)))
            val legacyId = dao.allCategories().single().id
            dao.insert(
                TransactionEntity(
                    amountMinor = 400,
                    currency = "CNY",
                    direction = "EXPENSE",
                    categoryId = legacyId,
                    occurredAtEpochMillis = 1_795_096_622_272L,
                    merchant = "",
                    note = "",
                    accountId = null,
                    source = "NOTIFICATION",
                    pendingConfirmation = true,
                    internalTransfer = false,
                    dedupeKey = "legacy",
                    redactedNotificationDigest = null,
                    refundOfId = null,
                    createdAtEpochMillis = 0,
                    updatedAtEpochMillis = 0,
                ),
            )
        } finally {
            legacy.close()
        }

        // 正常打开就会走 onOpen，迁移发生在这里
        val db = Room.databaseBuilder(app, AppDatabase::class.java, dbName)
            .addCallback(AppDatabase.categorySeedCallback())
            .build()
        val dao = db.transactionDao()
        val names = dao.allCategories().map { it.name }
        assertFalse("老分类名应该换掉了", LEGACY_PENDING_CATEGORY in names)
        assertTrue("新分类名要在", CATEGORY_UNSURE in names)

        val unsureId = dao.allCategories().single { it.name == CATEGORY_UNSURE }.id
        db.query("SELECT categoryId FROM transactions LIMIT 1", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("交易要跟着挂到新分类上", unsureId, cursor.getLong(0))
        }
        db.close()

        // 三条 SQL 必须能重复跑：每次启动都会再走一遍 onOpen
        val again = Room.databaseBuilder(app, AppDatabase::class.java, dbName)
            .addCallback(AppDatabase.categorySeedCallback())
            .build()
        try {
            assertEquals(
                "重复迁移不该多造分类",
                1,
                again.transactionDao().allCategories().count { it.name == CATEGORY_UNSURE },
            )
        } finally {
            again.close()
        }
        app.deleteDatabase(dbName)
        Unit
    }
}
