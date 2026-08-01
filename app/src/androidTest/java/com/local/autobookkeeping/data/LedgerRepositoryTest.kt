package com.local.autobookkeeping.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    @Test
    fun saveEditDeleteAndDedupe() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val repo = RoomLedgerRepository(db)

        try {
            val id = repo.save(sampleTransaction(dedupeKey = "wx-2800-1230"))
            assertNotNull(repo.findByDedupeKey("wx-2800-1230"))

            repo.save(sampleTransaction(id = id, dedupeKey = "wx-2800-1230", merchant = "Updated merchant"))
            assertEquals("Updated merchant", repo.findByDedupeKey("wx-2800-1230")?.merchant)

            repo.delete(id)
            assertNull(repo.findByDedupeKey("wx-2800-1230"))
        } finally {
            db.close()
        }
    }

    @Test
    fun observeTransactionsReturnsNewestFirst() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val repo = RoomLedgerRepository(db)

        try {
            repo.save(sampleTransaction(dedupeKey = "older"))
            repo.save(
                sampleTransaction(
                    dedupeKey = "newer",
                    merchant = "Newer merchant",
                    occurredAt = "2026-07-23T05:30:00Z",
                ),
            )

            assertEquals(
                listOf("Newer merchant", "Coffee shop"),
                repo.observeTransactions().first().map(Transaction::merchant),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun recognitionMetadataSurvivesSaveReadAndEdit() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val repo = RoomLedgerRepository(db)

        try {
            val id = repo.save(
                sampleTransaction().copy(
                    platform = PaymentPlatform.ALIPAY,
                    eventKind = TransactionEventKind.REFUND,
                    recognitionConfidence = 82,
                ),
            )

            val saved = repo.findById(id)!!
            assertEquals(PaymentPlatform.ALIPAY, saved.platform)
            assertEquals(TransactionEventKind.REFUND, saved.eventKind)
            assertEquals(82, saved.recognitionConfidence)

            repo.save(saved.copy(note = "edited"))
            val edited = repo.findById(id)!!
            assertEquals(PaymentPlatform.ALIPAY, edited.platform)
            assertEquals(TransactionEventKind.REFUND, edited.eventKind)
            assertEquals(82, edited.recognitionConfidence)
        } finally {
            db.close()
        }
    }

    @Test
    fun replaceAllIsAtomicWhenDedupeKeysConflict() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val repo = RoomLedgerRepository(db)

        try {
            repo.save(sampleTransaction(dedupeKey = "before-restore"))

            val constraint = try {
                repo.replaceAllInTransaction(
                    listOf(
                        sampleTransaction(dedupeKey = "duplicate"),
                        sampleTransaction(dedupeKey = "duplicate", merchant = "Another merchant"),
                    ),
                )
                throw AssertionError("Expected duplicate dedupe keys to violate the SQLite unique index")
            } catch (exception: SQLiteConstraintException) {
                exception
            }

            assertTrue(constraint.message?.contains("UNIQUE constraint failed") == true)
            assertNotNull(repo.findByDedupeKey("before-restore"))
            assertNull(repo.findByDedupeKey("duplicate"))
        } finally {
            db.close()
        }
    }

    @Test
    fun builtInCategoriesSeedTheCompleteDirectionIndependentUnion() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).addCallback(AppDatabase.categorySeedCallback()).build()

        try {
            assertEquals(
                EXPECTED_BUILT_IN_CATEGORIES.sorted(),
                db.transactionDao().categoryNames(),
            )
        } finally {
            db.close()
        }
    }

    private fun sampleTransaction(
        id: Long = 0,
        dedupeKey: String? = null,
        merchant: String = "Coffee shop",
        occurredAt: String = "2026-07-23T04:30:00Z",
    ) = Transaction(
        id = id,
        amountMinor = 2_800,
        direction = Direction.EXPENSE,
        category = "Food",
        occurredAt = Instant.parse(occurredAt),
        merchant = merchant,
        account = "Cash",
        dedupeKey = dedupeKey,
    )

    private companion object {
        val EXPECTED_BUILT_IN_CATEGORIES = listOf(
            "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果", "服饰", "娱乐", "美容", "通讯",
            "医疗", "学习", "游戏", "住房", "礼品", "宠物", "汽车", "数码", "办公", "运动", "捐赠", "彩票",
            "红包", "礼金", "转账", "其他", "工资", "奖金", "理财", "分红", "退款",
        )
    }
}
