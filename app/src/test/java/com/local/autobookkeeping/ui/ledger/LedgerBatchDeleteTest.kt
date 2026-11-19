package com.local.autobookkeeping.ui.ledger

import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.CATEGORY_UNSURE
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerBatchDeleteTest {
    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("Asia/Shanghai")

    /** 跟 [tx] 的 2026-11-18T04:00Z 落在同一个「今天」里。 */
    private val NOW = Instant.parse("2026-11-18T12:00:00Z")

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `repository deletes every id of a batch`() = runTest(dispatcher) {
        val repository = RecordingRepository(listOf(tx(1), tx(2), tx(3)))

        repository.deleteAll(listOf(1L, 3L))

        assertEquals(listOf(2L), repository.rows.value.map { it.id })
        assertEquals(listOf(1L, 3L), repository.deletedIds)
    }

    @Test
    fun `view model deletes the selected rows`() = runTest(dispatcher) {
        val repository = RecordingRepository(listOf(tx(1), tx(2), tx(3)))
        val viewModel = LedgerViewModel(repository, zone)
        advanceUntilIdle()

        viewModel.deleteTransactions(listOf(2L))
        advanceUntilIdle()

        assertEquals(listOf(1L, 3L), repository.rows.value.map { it.id })
        assertEquals(listOf(2L), repository.deletedIds)
    }

    @Test
    fun `empty selection does not touch the repository`() = runTest(dispatcher) {
        val repository = RecordingRepository(listOf(tx(1)))
        val viewModel = LedgerViewModel(repository, zone)
        advanceUntilIdle()

        viewModel.deleteTransactions(emptyList())
        advanceUntilIdle()

        assertTrue(repository.deletedIds.isEmpty())
        assertEquals(listOf(1L), repository.rows.value.map { it.id })
    }

    @Test
    fun `view model confirms only the rows still waiting for a human`() = runTest(dispatcher) {
        val repository = RecordingRepository(
            listOf(
                tx(1).copy(pendingConfirmation = true, category = CATEGORY_UNSURE),
                tx(2),
                tx(3).copy(pendingConfirmation = true, category = CATEGORY_UNSURE),
            ),
        )
        val viewModel = LedgerViewModel(repository, zone)
        advanceUntilIdle()

        viewModel.confirmTransactions(listOf(1L, 2L, 3L))
        advanceUntilIdle()

        assertTrue(repository.rows.value.none { it.pendingConfirmation })
        // 已经确认过的 2 号不该被白写一次
        assertEquals(listOf(1L, 3L), repository.savedTransactions.map { it.id })
        // 确认只是摘掉「待核对」，分类不替用户猜
        assertTrue(repository.savedTransactions.all { it.category == CATEGORY_UNSURE })
    }

    @Test
    fun `confirmed unsure rows start counting towards the day total`() = runTest(dispatcher) {
        val repository = RecordingRepository(
            listOf(tx(1).copy(pendingConfirmation = true, category = CATEGORY_UNSURE)),
        )
        val viewModel = LedgerViewModel(repository, zone)
        advanceUntilIdle()

        val before = calculateHomeSummary(repository.rows.value, NOW, zone)
        assertEquals(0L, before.expenseMinor)
        assertEquals(1, before.pendingCount)

        viewModel.confirmTransactions(listOf(1L))
        advanceUntilIdle()

        val after = calculateHomeSummary(repository.rows.value, NOW, zone)
        assertEquals(1_000L, after.expenseMinor)
        // 分类还空着，所以横幅上仍然算一笔待分类
        assertEquals(1, after.pendingCount)
    }

    private fun tx(id: Long) = Transaction(
        id = id,
        amountMinor = 1_000,
        direction = Direction.EXPENSE,
        category = "餐饮",
        account = "支付宝",
        merchant = "面馆$id",
        occurredAt = Instant.parse("2026-11-18T04:00:00Z"),
    )

    private class RecordingRepository(initial: List<Transaction>) : LedgerRepository {
        val rows = MutableStateFlow(initial)
        val deletedIds = mutableListOf<Long>()
        val savedTransactions = mutableListOf<Transaction>()

        override fun observeTransactions(): Flow<List<Transaction>> = rows
        override suspend fun save(transaction: Transaction): Long {
            savedTransactions += transaction
            rows.value = rows.value.map { if (it.id == transaction.id) transaction else it }
            return transaction.id
        }

        override suspend fun delete(id: Long) {
            deletedIds += id
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun findByDedupeKey(key: String): Transaction? = null
        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) {
            rows.value = transactions
        }
    }
}
