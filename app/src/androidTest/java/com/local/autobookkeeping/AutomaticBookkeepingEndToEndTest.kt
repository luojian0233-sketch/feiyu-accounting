package com.local.autobookkeeping

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.StatisticsSummary
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.domain.statistics.StatisticsEngine
import com.local.autobookkeeping.ui.statistics.RangeMode
import com.local.autobookkeeping.ui.statistics.STATISTICS_TOTAL_TAG
import com.local.autobookkeeping.ui.statistics.StatisticsScreen
import com.local.autobookkeeping.ui.statistics.StatisticsUiState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticBookkeepingEndToEndTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var graph: ApplicationGraph

    @Before
    fun createIsolatedGraph() {
        val application = ApplicationProvider.getApplicationContext<AutoBookkeepingApplication>()
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
            .addCallback(AppDatabase.categorySeedCallback())
            .build()
        graph = ApplicationGraph(
            application = application,
            databaseProvider = { database },
        )
    }

    @After
    fun closeIsolatedDatabase() {
        database.close()
    }

    @Test
    fun confirmedSanitizedExpenseAndIncomeAppearExactlyOnceInLedgerAndEveryStatistic() =
        runBlocking {
            val anchor = LocalDate.now(FIXED_CLOCK)
            assertEquals(LocalDate.of(2026, 7, 29), anchor)

            val expense = candidate(
                platform = PaymentPlatform.WECHAT,
                channel = CaptureChannel.NOTIFICATION,
                direction = Direction.EXPENSE,
                amountMinor = 1_234,
                merchant = "Sanitized Cafe",
                digest = EXPENSE_DIGEST,
            )
            val income = candidate(
                platform = PaymentPlatform.ALIPAY,
                channel = CaptureChannel.SCREENSHOT,
                direction = Direction.INCOME,
                amountMinor = 5_678,
                merchant = "Sanitized Client",
                digest = INCOME_DIGEST,
            )

            val pendingExpense = graph.recognitionCoordinator.process(expense).pendingId()
            val pendingIncome = graph.recognitionCoordinator.process(income).pendingId()
            confirm(pendingExpense, EXPENSE_CATEGORY)
            confirm(pendingIncome, INCOME_CATEGORY)

            assertEquals(RecognitionOutcome.Duplicate, graph.recognitionCoordinator.process(expense))
            assertEquals(RecognitionOutcome.Duplicate, graph.recognitionCoordinator.process(income))

            val ledger = graph.ledgerRepository.observeTransactions().first()
            assertEquals(2, ledger.size)
            assertEquals(1, ledger.count { it.direction == Direction.EXPENSE })
            assertEquals(1, ledger.count { it.direction == Direction.INCOME })
            assertEquals(1, ledger.count { it.source == Source.NOTIFICATION })
            assertEquals(1, ledger.count { it.source == Source.SCREENSHOT })
            assertTrue(ledger.none { it.pendingConfirmation })

            val ranges = listOf(
                RangeMode.DAY to DateRange(
                    LocalDate.of(2026, 7, 29),
                    LocalDate.of(2026, 7, 29),
                    ZONE,
                ),
                RangeMode.WEEK to DateRange(
                    LocalDate.of(2026, 7, 27),
                    LocalDate.of(2026, 8, 2),
                    ZONE,
                ),
                RangeMode.MONTH to DateRange(
                    LocalDate.of(2026, 7, 1),
                    LocalDate.of(2026, 7, 31),
                    ZONE,
                ),
                RangeMode.YEAR to DateRange(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 12, 31),
                    ZONE,
                ),
            )
            val summaries = ranges.associate { (mode, range) ->
                mode to mapOf(
                    Direction.EXPENSE to StatisticsEngine.summarize(
                        ledger,
                        range,
                        Direction.EXPENSE,
                    ),
                    Direction.INCOME to StatisticsEngine.summarize(
                        ledger,
                        range,
                        Direction.INCOME,
                    ),
                )
            }
            summaries.values.forEach { byDirection ->
                assertSummary(byDirection.getValue(Direction.EXPENSE), 1_234, EXPENSE_CATEGORY)
                assertSummary(byDirection.getValue(Direction.INCOME), 5_678, INCOME_CATEGORY)
            }

            var state by mutableStateOf(
                stateFor(
                    mode = RangeMode.DAY,
                    range = ranges.first().second,
                    direction = Direction.EXPENSE,
                    summary = summaries.getValue(RangeMode.DAY).getValue(Direction.EXPENSE),
                ),
            )
            compose.setContent { StatisticsScreen(state = state) }
            ranges.forEach { (mode, range) ->
                assertPieDirection(
                    update = {
                        state = stateFor(
                            mode,
                            range,
                            Direction.EXPENSE,
                            summaries.getValue(mode).getValue(Direction.EXPENSE),
                        )
                    },
                    amount = "12.34",
                    category = EXPENSE_CATEGORY,
                )
                assertPieDirection(
                    update = {
                        state = stateFor(
                            mode,
                            range,
                            Direction.INCOME,
                            summaries.getValue(mode).getValue(Direction.INCOME),
                        )
                    },
                    amount = "56.78",
                    category = INCOME_CATEGORY,
                )
            }
        }

    private suspend fun confirm(transactionId: Long, category: String) {
        val pending = requireNotNull(graph.ledgerRepository.findById(transactionId))
        assertTrue(pending.pendingConfirmation)
        graph.recognitionStore.confirmAndRemember(
            pending.copy(category = category, pendingConfirmation = false),
        )
        assertFalse(requireNotNull(graph.ledgerRepository.findById(transactionId)).pendingConfirmation)
    }

    private fun RecognitionOutcome.pendingId(): Long {
        assertTrue(this is RecognitionOutcome.Saved && pending)
        return (this as RecognitionOutcome.Saved).transactionId
    }

    private fun assertSummary(summary: StatisticsSummary, amount: Long, category: String) {
        assertEquals(amount, summary.totalMinor)
        assertEquals(1, summary.categories.size)
        assertEquals(category, summary.categories.single().category)
        assertEquals(amount, summary.categories.single().amountMinor)
    }

    private fun assertPieDirection(update: () -> Unit, amount: String, category: String) {
        compose.runOnIdle(update)
        compose.onNodeWithTag(STATISTICS_TOTAL_TAG).assertTextContains(amount)
        compose.onNode(
            hasTestTag("statistics-pie") and
                hasContentDescription(category, substring = true),
        ).assertIsDisplayed()
    }

    private fun stateFor(
        mode: RangeMode,
        range: DateRange,
        direction: Direction,
        summary: StatisticsSummary,
    ) = StatisticsUiState(
        rangeMode = mode,
        range = range,
        direction = direction,
        summary = summary,
    )

    private fun candidate(
        platform: PaymentPlatform,
        channel: CaptureChannel,
        direction: Direction,
        amountMinor: Long,
        merchant: String,
        digest: String,
    ) = RecognitionCandidate(
        platform = platform,
        channel = channel,
        eventKind = TransactionEventKind.PAYMENT,
        amountMinor = amountMinor,
        direction = direction,
        merchant = merchant,
        account = null,
        occurredAt = FIXED_CLOCK.instant(),
        confidence = 91,
        evidenceDigest = digest,
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-29T08:30:00Z"), ZONE)
        const val EXPENSE_CATEGORY = "Task13 Expense"
        const val INCOME_CATEGORY = "Task13 Income"
        val EXPENSE_DIGEST = "11".repeat(32)
        val INCOME_DIGEST = "22".repeat(32)
    }
}
