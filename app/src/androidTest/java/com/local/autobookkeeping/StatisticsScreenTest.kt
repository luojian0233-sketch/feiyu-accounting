package com.local.autobookkeeping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.local.autobookkeeping.domain.model.CategoryTotal
import com.local.autobookkeeping.domain.model.DateRange
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.StatisticsSummary
import com.local.autobookkeeping.ui.statistics.RangeMode
import com.local.autobookkeeping.ui.statistics.StatisticsScreen
import com.local.autobookkeeping.ui.statistics.StatisticsUiState
import com.local.autobookkeeping.ui.statistics.statisticsCategoryRowTag
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StatisticsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateHasNoFakeChart() {
        compose.setContent {
            StatisticsScreen(state = state(summary = StatisticsSummary(0, emptyList())))
        }

        compose.onNodeWithText("暂无支出记录").assertIsDisplayed()
        compose.onNodeWithTag("statistics-pie").assertDoesNotExist()
    }

    @Test
    fun chartSemanticsAndCategoryDrilldownRemainReadableWithoutColor() {
        var selected: CategoryTotal? = null
        compose.setContent {
            StatisticsScreen(
                state = state(
                    summary = StatisticsSummary(
                        totalMinor = 10_000,
                        categories = listOf(
                            CategoryTotal("餐饮", 7_500),
                            CategoryTotal("交通", 2_500),
                        ),
                    ),
                ),
                onCategoryClick = { selected = it },
            )
        }

        compose.onNodeWithContentDescription("餐饮，75.00元，75.0%；交通，25.00元，25.0%")
            .assertIsDisplayed()
        compose.onNodeWithText("餐饮").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("餐饮", selected?.category) }
    }

    @Test
    fun categoryRankingTagExposesMergedCategoryAndExactAmount() {
        compose.setContent {
            StatisticsScreen(
                state = state(
                    summary = StatisticsSummary(
                        totalMinor = 2_800,
                        categories = listOf(CategoryTotal("红包", 2_800)),
                    ),
                ),
            )
        }

        compose.onNodeWithTag(statisticsCategoryRowTag("红包"))
            .assert(hasText("红包") and hasText("¥28.00"))
    }

    @Test
    fun rangeAndDirectionControlsInvokeCallbacks() {
        var direction: Direction? = null
        var move = 0
        compose.setContent {
            StatisticsScreen(
                state = state(StatisticsSummary(0, emptyList())),
                onDirectionSelected = { direction = it },
                onMoveRange = { move = it },
            )
        }

        compose.onNodeWithText("收入").performClick()
        compose.onNodeWithContentDescription("下一时间段").performClick()
        compose.runOnIdle {
            assertEquals(Direction.INCOME, direction)
            assertEquals(1, move)
        }
    }

    @Test
    fun compactStatisticsErrorIsVisible() {
        compose.setContent {
            StatisticsScreen(
                state = state(StatisticsSummary(0, emptyList())).copy(
                    errorMessage = "统计金额过大，请检查记录",
                ),
            )
        }

        compose.onNodeWithTag("statistics-error").assertIsDisplayed()
        compose.onNodeWithText("统计金额过大，请检查记录").assertIsDisplayed()
    }

    private fun state(summary: StatisticsSummary) = StatisticsUiState(
        rangeMode = RangeMode.MONTH,
        range = DateRange(
            LocalDate.parse("2026-07-01"),
            LocalDate.parse("2026-07-31"),
            ZoneId.of("Asia/Shanghai"),
        ),
        direction = Direction.EXPENSE,
        summary = summary,
    )
}
