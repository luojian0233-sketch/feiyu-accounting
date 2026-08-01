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
            val receipt = sampleReceipt(SECOND_DIGEST)

            store.attachReceipt(id, receipt)

            assertEquals(receipt.copy(transactionId = id), store.findReceipt(SECOND_DIGEST))

            repository.delete(id)
            assertNull(store.findReceipt(SECOND_DIGEST))
        }
    }

    @Test
    fun findMatchesIncludesExactFifteenMinuteEndpointsAndBothConfirmationStates() = runTest {
        withStore { store, repository, _ ->
            repository.save(sampleRecognizedTransaction(merchant = "lower outside", occurredAt = "2026-07-23T03:44:59.999Z"))
            repository.save(
                sampleRecognizedTransaction(
                    merchant = "lower endpoint pending",
                    occurredAt = "2026-07-23T03:45:00Z",
                    pendingConfirmation = true,
                ),
            )
            repository.save(
                sampleRecognizedTransaction(
                    merchant = "upper endpoint confirmed",
                    occurredAt = "2026-07-23T04:15:00Z",
                    pendingConfirmation = false,
                ),
            )
            repository.save(sampleRecognizedTransaction(merchant = "upper outside", occurredAt = "2026-07-23T04:15:00.001Z"))

            val matches = store.findMatches(
                RecognitionMatchQuery(
                    platform = PaymentPlatform.WECHAT,
                    direction = Direction.EXPENSE,
                    amountMinor = 2_800,
                    eventKind = TransactionEventKind.PAYMENT,
                    from = Instant.parse("2026-07-23T03:45:00Z"),
                    to = Instant.parse("2026-07-23T04:15:00Z"),
                ),
            )

            assertEquals(
                listOf("upper endpoint confirmed", "lower endpoint pending"),
                matches.map(Transaction::merchant),
            )
            assertEquals(listOf(false, true), matches.map(Transaction::pendingConfirmation))
        }
    }

    @Test
    fun categoryVisibilityReflectsHiddenState() = runTest {
        withStore { store, repository, database ->
            repository.save(sampleRecognizedTransaction())
            assertTrue(store.isCategoryVisible("Food"))
            val category = database.transactionDao().findCategoryByName("Food")!!
            database.transactionDao().setCategoryHidden(category.id, true)
            assertTrue(!store.isCategoryVisible("Food"))
        }
    }

    @Test
    fun findRefundOriginReturnsTheOnlyMatchingCandidate() = runTest {
        withStore { store, repository, _ ->
            val originId = repository.save(sampleRecognizedTransaction(occurredAt = "2026-07-23T04:00:00Z"))

            val refundOrigin = store.findRefundOrigin(
                sampleRefundCandidate(),
            )
            assertEquals(originId, refundOrigin?.id)
        }
    }

    @Test
    fun findRefundOriginReturnsNullWhenMultipleCandidatesMatch() = runTest {
        withStore { store, repository, _ ->
            repository.save(sampleRecognizedTransaction(occurredAt = "2026-07-22T04:00:00Z"))
            repository.save(sampleRecognizedTransaction(occurredAt = "2026-07-23T04:00:00Z"))

            assertNull(store.findRefundOrigin(sampleRefundCandidate()))
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

    @Test
    fun confirmAndRememberRollsBackTransactionAndCreatedReferencesWhenRuleInsertFails() = runTest {
        withStore { store, repository, database ->
            val id = repository.save(sampleRecognizedTransaction())
            val original = repository.findById(id)!!
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fail_merchant_rule_insert
                BEFORE INSERT ON merchant_rules
                BEGIN
                    SELECT RAISE(ABORT, 'forced merchant rule failure');
                END
                """.trimIndent(),
            )

            val failed = runCatching {
                store.confirmAndRemember(
                    original.copy(
                        merchant = "Rollback merchant",
                        category = "Rollback category",
                        account = "Rollback account",
                        pendingConfirmation = false,
                    ),
                )
            }.isFailure

            assertTrue(failed)
            assertEquals(original, repository.findById(id))
            assertNull(database.transactionDao().findCategoryByName("Rollback category"))
            assertNull(database.transactionDao().findAccountByName("Rollback account"))
            assertTrue(database.transactionDao().allMerchantRules().isEmpty())
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
        pendingConfirmation: Boolean = true,
    ) = Transaction(
        amountMinor = 2_800,
        direction = Direction.EXPENSE,
        category = "Food",
        occurredAt = Instant.parse(occurredAt),
        merchant = merchant,
        account = "Cash",
        source = Source.PAGE_RECOGNITION,
        pendingConfirmation = pendingConfirmation,
        platform = PaymentPlatform.WECHAT,
        eventKind = eventKind,
        recognitionConfidence = 91,
    )

    private fun sampleRefundCandidate() = RecognitionCandidate(
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
