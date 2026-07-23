package com.local.autobookkeeping.ui.entry

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `rapid save executes repository once and returns Result`() = runTest(dispatcher) {
        val repository = DelayedRepository()
        val viewModel = EntryViewModel(repository)
        viewModel.update { it.copy(amountText = "28.00", category = "Food") }

        val first = async { viewModel.save() }
        testScheduler.runCurrent()
        val second = async { viewModel.save() }
        testScheduler.runCurrent()

        assertTrue(second.await().isFailure)
        assertEquals(1, repository.saveCalls)
        repository.release.complete(Unit)
        assertEquals(41L, first.await().getOrThrow())
    }

    @Test
    fun `rapid delete executes repository once`() = runTest(dispatcher) {
        val repository = DelayedRepository()
        val viewModel = EntryViewModel(repository)
        viewModel.edit(transaction(id = 9))

        val first = async { viewModel.delete() }
        testScheduler.runCurrent()
        val second = async { viewModel.delete() }
        testScheduler.runCurrent()

        assertTrue(second.await().isFailure)
        assertEquals(1, repository.deleteCalls)
        repository.release.complete(Unit)
        assertTrue(first.await().isSuccess)
    }

    private fun transaction(id: Long) = Transaction(
        id = id,
        amountMinor = 100,
        direction = Direction.EXPENSE,
        category = "Food",
        occurredAt = Instant.parse("2026-07-22T04:00:00Z"),
    )

    private class DelayedRepository : LedgerRepository {
        val release = CompletableDeferred<Unit>()
        var saveCalls = 0
        var deleteCalls = 0
        private val rows = MutableStateFlow<List<Transaction>>(emptyList())

        override fun observeTransactions(): Flow<List<Transaction>> = rows
        override suspend fun save(transaction: Transaction): Long {
            saveCalls += 1
            release.await()
            return 41
        }

        override suspend fun delete(id: Long) {
            deleteCalls += 1
            release.await()
        }

        override suspend fun findByDedupeKey(key: String): Transaction? = null
        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) = Unit
    }
}
