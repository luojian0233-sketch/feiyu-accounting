package com.local.autobookkeeping

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.domain.model.CaptureChannel
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.PaymentPlatform
import com.local.autobookkeeping.domain.model.RecognitionCandidate
import com.local.autobookkeeping.domain.model.RecognitionOutcome
import com.local.autobookkeeping.domain.model.TransactionEventKind
import com.local.autobookkeeping.ui.statistics.RangeMode
import com.local.autobookkeeping.ui.statistics.STATISTICS_TOTAL_TAG
import com.local.autobookkeeping.ui.statistics.StatisticsScreen
import com.local.autobookkeeping.ui.statistics.StatisticsViewModel
import com.local.autobookkeeping.ui.statistics.statisticsCategoryRowTag
import com.local.autobookkeeping.ui.statistics.statisticsDirectionTag
import com.local.autobookkeeping.ui.statistics.statisticsRangeModeTag
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
    fun confirmedRecognitionDrivesViewModelRangeAndDirectionControlsAtShanghaiBoundaries() {
        val fixtures = boundaryFixtures()
        runBlocking {
            fixtures.forEach { fixture ->
                confirm(graph.recognitionCoordinator.process(fixture.candidate), fixture.category)
            }

            assertEquals(
                RecognitionOutcome.Duplicate,
                graph.recognitionCoordinator.process(fixtures.first().candidate),
            )
            val ledger = graph.ledgerRepository.observeTransactions().first()
            assertEquals(18, ledger.size)
            assertEquals(9, ledger.count { it.direction == Direction.EXPENSE })
            assertEquals(9, ledger.count { it.direction == Direction.INCOME })
            assertTrue(ledger.none { it.pendingConfirmation })
        }

        val viewModel = StatisticsViewModel(
            repository = graph.ledgerRepository,
            clock = FIXED_CLOCK,
            zone = ZONE,
        )
        compose.setContent {
            val state by viewModel.state.collectAsState()
            StatisticsScreen(
                state = state,
                onModeSelected = viewModel::selectMode,
                onDirectionSelected = viewModel::selectDirection,
                onMoveRange = viewModel::moveRange,
                onDateSelected = viewModel::selectDate,
                onCustomRangeSelected = { start, end -> viewModel.selectCustom(start, end) },
            )
        }

        assertSelection(
            viewModel,
            RangeMode.DAY,
            Direction.EXPENSE,
            "2026-07-29",
            "2026-07-29",
            "1.00",
            EXPENSE_CATEGORY,
            INCOME_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.DAY,
            Direction.INCOME,
            "2026-07-29",
            "2026-07-29",
            "1.10",
            INCOME_CATEGORY,
            EXPENSE_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.WEEK,
            Direction.EXPENSE,
            "2026-07-27",
            "2026-08-02",
            "13.00",
            EXPENSE_CATEGORY,
            INCOME_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.WEEK,
            Direction.INCOME,
            "2026-07-27",
            "2026-08-02",
            "14.30",
            INCOME_CATEGORY,
            EXPENSE_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.MONTH,
            Direction.EXPENSE,
            "2026-07-01",
            "2026-07-31",
            "10.00",
            EXPENSE_CATEGORY,
            INCOME_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.MONTH,
            Direction.INCOME,
            "2026-07-01",
            "2026-07-31",
            "11.00",
            INCOME_CATEGORY,
            EXPENSE_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.YEAR,
            Direction.EXPENSE,
            "2026-01-01",
            "2026-12-31",
            "28.00",
            EXPENSE_CATEGORY,
            INCOME_CATEGORY,
        )
        assertSelection(
            viewModel,
            RangeMode.YEAR,
            Direction.INCOME,
            "2026-01-01",
            "2026-12-31",
            "30.80",
            INCOME_CATEGORY,
            EXPENSE_CATEGORY,
        )
    }

    private suspend fun confirm(outcome: RecognitionOutcome, category: String) {
        assertTrue(outcome is RecognitionOutcome.Saved)
        val transactionId = (outcome as RecognitionOutcome.Saved).transactionId
        val row = requireNotNull(graph.ledgerRepository.findById(transactionId))
        graph.recognitionStore.confirmAndRemember(
            row.copy(category = category, pendingConfirmation = false),
        )
        assertFalse(requireNotNull(graph.ledgerRepository.findById(transactionId)).pendingConfirmation)
    }

    private fun assertSelection(
        viewModel: StatisticsViewModel,
        mode: RangeMode,
        direction: Direction,
        start: String,
        end: String,
        amount: String,
        category: String,
        oppositeCategory: String,
    ) {
        compose.onNodeWithTag(statisticsRangeModeTag(mode)).performClick()
        compose.onNodeWithTag(statisticsDirectionTag(direction)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            val state = viewModel.state.value
            state.rangeMode == mode &&
                state.direction == direction &&
                state.range.start == LocalDate.parse(start) &&
                state.range.endInclusive == LocalDate.parse(end)
        }

        compose.onNodeWithTag(statisticsRangeModeTag(mode)).assertIsSelected()
        compose.onNodeWithTag(statisticsDirectionTag(direction)).assertIsSelected()
        compose.onNodeWithTag(STATISTICS_TOTAL_TAG).assertTextContains(amount, substring = true)
        compose.onNode(
            hasTestTag("statistics-pie") and
                hasContentDescription(category, substring = true),
        ).assertIsDisplayed()
        compose.onNodeWithTag(statisticsCategoryRowTag(category)).assertIsDisplayed()
        compose.onNodeWithTag(statisticsCategoryRowTag(oppositeCategory)).assertDoesNotExist()
    }

    private fun boundaryFixtures(): List<Fixture> {
        val instants = listOf(
            "2026-07-28T16:00:00Z",
            "2026-07-28T15:59:59.999Z",
            "2026-07-29T16:00:00Z",
            "2026-07-26T15:59:59.999Z",
            "2026-08-02T16:00:00Z",
            "2026-06-30T15:59:59.999Z",
            "2026-07-31T16:00:00Z",
            "2025-12-31T15:59:59.999Z",
            "2026-12-31T16:00:00Z",
        )
        val expenseAmounts = listOf(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L)
        val incomeAmounts = listOf(110L, 220L, 330L, 440L, 550L, 660L, 770L, 880L, 990L)
        return instants.flatMapIndexed { index, instant ->
            listOf(
                Fixture(
                    candidate(
                        index = index * 2,
                        direction = Direction.EXPENSE,
                        amountMinor = expenseAmounts[index],
                        occurredAt = Instant.parse(instant),
                    ),
                    EXPENSE_CATEGORY,
                ),
                Fixture(
                    candidate(
                        index = index * 2 + 1,
                        direction = Direction.INCOME,
                        amountMinor = incomeAmounts[index],
                        occurredAt = Instant.parse(instant),
                    ),
                    INCOME_CATEGORY,
                ),
            )
        }
    }

    private fun candidate(
        index: Int,
        direction: Direction,
        amountMinor: Long,
        occurredAt: Instant,
    ) = RecognitionCandidate(
        platform = if (direction == Direction.EXPENSE) PaymentPlatform.WECHAT else PaymentPlatform.ALIPAY,
        channel = if (direction == Direction.EXPENSE) CaptureChannel.NOTIFICATION else CaptureChannel.SCREENSHOT,
        eventKind = if (direction == Direction.EXPENSE) {
            TransactionEventKind.PAYMENT
        } else {
            TransactionEventKind.RECEIPT
        },
        amountMinor = amountMinor,
        direction = direction,
        merchant = "BoundaryMerchant$index",
        account = null,
        occurredAt = occurredAt,
        confidence = 91,
        evidenceDigest = "%02x".format(index + 1).repeat(32),
    )

    private data class Fixture(
        val candidate: RecognitionCandidate,
        val category: String,
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZONE)
        const val EXPENSE_CATEGORY = "Task13 Expense"
        const val INCOME_CATEGORY = "Task13 Income"
    }
}
