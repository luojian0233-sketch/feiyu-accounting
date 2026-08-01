package com.local.autobookkeeping.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionReceipt
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.domain.model.TransactionEventKind
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecognitionStoreTest {
    @Test
    fun createRecognizedTransactionAndReceiptIsAtomic() = runTest {
        withStore { store, repository, _ ->
            val id = store.create(sampleRecognizedTransaction(), sampleReceipt(DIGEST))
            assertEquals(id, store.findReceipt(DIGEST)?.transactionId)

            expectDuplicate(DIGEST) {
                store.create(
                    sampleRecognizedTransaction(merchant = "Must roll back"),
                    sampleReceipt(DIGEST),
                )
            }

            assertEquals(1, repository.observeTransactions().first().size)
        }
    }

    @Test
    fun updateRollsBackTransactionWhenReceiptDigestAlreadyExists() = runTest {
        withStore { store, repository, _ ->
            val id = store.create(sampleRecognizedTransaction(), sampleReceipt(DIGEST))

            expectDuplicate(DIGEST) {
                store.update(
                    repository.findById(id)!!.copy(merchant = "Must roll back"),
                    sampleReceipt(DIGEST),
                )
            }

            assertEquals("Coffee shop", repository.findById(id)?.merchant)
        }
    }

    @Test
    fun attachReceiptAndLookupRoundTrip() = runTest {
        withStore { store, repository, _ ->
            val id = repository.save(sampleRecognizedTransaction())

            store.attachReceipt(id, sampleReceipt(SECOND_DIGEST))

            assertEquals(id, store.findReceipt(SECOND_DIGEST)?.transactionId)
        }
    }

    @Test
    fun matchingVisibilityAndRefundOriginQueriesReturnDomainTransactions() = runTest {
        withStore { store, repository, database ->
            val originId = repository.save(
                sampleRecognizedTransaction(
                    occurredAt = "2026-07-23T04:00:00Z",
                    eventKind = TransactionEventKind.PAYMENT,
                ),
            )
            repository.save(
                sampleRecognizedTransaction(
                    merchant = "Outside window",
                    occurredAt = "2026-07-21T04:00:00Z",
                ),
            )

            val matches = store.findMatches(
                RecognitionMatchQuery(
                    platform = PaymentPlatform.WECHAT,
                    direction = Direction.EXPENSE,
                    amountMinor = 2_800,
                    eventKind = TransactionEventKind.PAYMENT,
                    from = Instant.parse("2026-07-23T03:59:00Z"),
                    to = Instant.parse("2026-07-23T04:01:00Z"),
                ),
            )

            assertEquals(listOf(originId), matches.map(Transaction::id))
            assertTrue(store.isCategoryVisible("Food"))
            val category = database.transactionDao().findCategoryByName("Food")!!
            database.transactionDao().setCategoryHidden(category.id, true)
            assertTrue(!store.isCategoryVisible("Food"))

            val refundOrigin = store.findRefundOrigin(
                RecognitionCandidate(
                    platform = PaymentPlatform.WECHAT,
                    channel = CaptureChannel.SCREENSHOT,
                    eventKind = TransactionEventKind.REFUND,
                    amountMinor = 2_800,
                    direction = Direction.INCOME,
                    merchant = "Coffee shop",
                    account = null,
                    occurredAt = Instant.parse("2026-07-24T04:00:00Z"),
                    confidence = 90,
                    evidenceDigest = THIRD_DIGEST,
                ),
            )
            assertEquals(originId, refundOrigin?.id)
        }
    }

    @Test
    fun confirmAndRememberUpsertsNormalizedRuleWhileBlankMerchantCreatesNone() = runTest {
        withStore { store, repository, database ->
            val pendingId = repository.save(
                sampleRecognizedTransaction(merchant = "  Local Shop  ").copy(pendingConfirmation = true),
            )
            val confirmed = repository.findById(pendingId)!!.copy(
                category = "Shopping",
                account = "Wallet",
                pendingConfirmation = false,
            )

            assertEquals(pendingId, store.confirmAndRemember(confirmed))
            assertEquals(
                MerchantRuleMatch("local shop", "Shopping", "Wallet", 0),
                store.findMerchantRule("local shop"),
            )

            store.rememberMerchantRule(" LOCAL SHOP ", "Food", null)
            assertEquals(
                MerchantRuleMatch("local shop", "Food", null, 0),
                store.findMerchantRule("local shop"),
            )

            val blankId = repository.save(
                sampleRecognizedTransaction(merchant = "").copy(
                    dedupeKey = "blank-merchant",
                    pendingConfirmation = true,
                ),
            )
            store.confirmAndRemember(repository.findById(blankId)!!.copy(pendingConfirmation = false))
            assertNull(store.findMerchantRule(""))
            assertEquals(1, database.transactionDao().allMerchantRules().size)
            assertNotNull(repository.findById(blankId))
        }
    }

    private suspend fun withStore(
        block: suspend (RecognitionStore, LedgerRepository, AppDatabase) -> Unit,
    ) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        try {
            block(RoomRecognitionStore(database), RoomLedgerRepository(database), database)
        } finally {
            database.close()
        }
    }

    private suspend fun expectDuplicate(digest: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a duplicate recognition receipt")
        } catch (failure: DuplicateRecognitionReceiptException) {
            assertEquals(digest, failure.digest)
        }
    }

    private fun sampleRecognizedTransaction(
        merchant: String = "Coffee shop",
        occurredAt: String = "2026-07-23T04:00:00Z",
        eventKind: TransactionEventKind = TransactionEventKind.PAYMENT,
    ) = Transaction(
        amountMinor = 2_800,
        direction = Direction.EXPENSE,
        category = "Food",
        occurredAt = Instant.parse(occurredAt),
        merchant = merchant,
        account = "Cash",
        source = Source.PAGE_RECOGNITION,
        pendingConfirmation = true,
        platform = PaymentPlatform.WECHAT,
        eventKind = eventKind,
        recognitionConfidence = 91,
    )

    private fun sampleReceipt(digest: String) = RecognitionReceipt(
        channel = CaptureChannel.SCREENSHOT,
        platform = PaymentPlatform.WECHAT,
        eventKind = TransactionEventKind.PAYMENT,
        observedAt = Instant.parse("2026-07-23T04:00:05Z"),
        digest = digest,
    )

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SECOND_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val THIRD_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
