package com.local.autobookkeeping.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
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
    fun replaceAllIsAtomicWhenDedupeKeysConflict() = runTest {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        val repo = RoomLedgerRepository(db)

        try {
            repo.save(sampleTransaction(dedupeKey = "before-restore"))

            var failed = false
            try {
                repo.replaceAllInTransaction(
                    listOf(
                        sampleTransaction(dedupeKey = "duplicate"),
                        sampleTransaction(dedupeKey = "duplicate", merchant = "Another merchant"),
                    ),
                )
            } catch (_: Exception) {
                failed = true
            }

            assertTrue(failed)
            assertNotNull(repo.findByDedupeKey("before-restore"))
            assertNull(repo.findByDedupeKey("duplicate"))
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
}
