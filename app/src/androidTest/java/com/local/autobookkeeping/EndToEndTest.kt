package com.local.autobookkeeping

import android.view.View
import android.widget.DatePicker
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.local.autobookkeeping.ui.BOOKKEEPING_ZONE
import com.local.autobookkeeping.ui.entry.AMOUNT_FIELD_TAG
import com.local.autobookkeeping.ui.entry.AMOUNT_KEYPAD_TAG
import com.local.autobookkeeping.ui.entry.ENTRY_DATE_TAG
import com.local.autobookkeeping.ui.entry.KEYPAD_DONE_TAG
import com.local.autobookkeeping.ui.ledger.LEDGER_LIST_TAG
import com.local.autobookkeeping.ui.statistics.STATISTICS_LIST_TAG
import com.local.autobookkeeping.ui.statistics.STATISTICS_TOTAL_TAG
import com.local.autobookkeeping.ui.statistics.statisticsCategoryRowTag
import com.local.autobookkeeping.ui.theme.ThemeStyle
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class EndToEndTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var application: ThemeSwitchTestApplication
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun resetRunnerOwnedGraph() {
        application = ApplicationProvider.getApplicationContext()
        runBlocking {
            application.applicationGraph.ledgerRepository.replaceAllInTransaction(emptyList())
            application.applicationGraph.settingsRepository.setTheme(ThemeStyle.CLEAR_MINT)
        }
    }

    @After
    fun resetJourneyDataWithoutClosingSharedGraph() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
        if (::application.isInitialized) {
            runBlocking {
                application.applicationGraph.ledgerRepository.replaceAllInTransaction(emptyList())
                application.applicationGraph.settingsRepository.setTheme(ThemeStyle.CLEAR_MINT)
            }
        }
    }

    @Test
    fun completePersonalLedgerJourney() {
        val journeyDate = Instant.now().atZone(BOOKKEEPING_ZONE).toLocalDate()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        createManualTransaction(amount = "28.00", date = journeyDate, income = false)
        createManualTransaction(amount = "100.00", date = journeyDate, income = true)
        assertLedgerAmount("+¥100.00")
        createManualTransaction(
            amount = "500.00",
            date = journeyDate,
            income = false,
            internalTransfer = true,
        )

        compose.onNodeWithTag("nav-STATISTICS").performClick()
        selectStatisticsMonth(journeyDate)
        assertMonthlyStatistics(direction = "支出", amount = "28.00")
        assertMonthlyStatistics(direction = "收入", amount = "100.00")

        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithText("小票账本").performClick()
        waitForTag(
            tag = "active-theme-RECEIPT_LEDGER",
            description = "小票账本主题写入并应用",
        )
        compose.onNodeWithTag("active-theme-RECEIPT_LEDGER").assertIsDisplayed()
        compose.onNodeWithTag("nav-LEDGER").performClick()
        assertLedgerAmount("-¥28.00")
        assertLedgerAmount("+¥100.00")
        assertLedgerAmount("-¥500.00")
    }

    private fun createManualTransaction(
        amount: String,
        date: LocalDate,
        income: Boolean,
        internalTransfer: Boolean = false,
    ) {
        compose.onNodeWithContentDescription("记一笔").performClick()
        if (income) {
            compose.onNodeWithText("收入").performClick()
        }
        typeAmount(amount)
        setEntryDate(date)
        compose.onNodeWithTag("entry-category-field").performScrollTo().performClick()
        compose.onNodeWithTag("category-grid").assertIsDisplayed()
        compose.onAllNodesWithTag("nav-HOME").assertCountEquals(0)
        compose.onNodeWithTag("category-grid")
            .performScrollToNode(hasTestTag("category-choice-红包"))
        compose.onNodeWithTag("category-choice-红包").performClick()
        compose.onNodeWithTag("entry-category-field").assertTextContains("红包")
        if (internalTransfer) {
            val transferLabel = compose.onNodeWithText("内部转账")
            transferLabel.performScrollTo()
            compose.onNode(isToggleable()).performClick()
        }
        compose.onNodeWithText("保存记录").performScrollTo().performClick()
        waitForTag("screen-账单", "保存 $amount 后返回账单")
    }

    /** 金额走局内键盘：点金额框唤出，再逐位点按键，最后「完成」收起。 */
    private fun typeAmount(amount: String) {
        compose.onNodeWithTag(AMOUNT_FIELD_TAG).performClick()
        compose.onNodeWithTag(AMOUNT_KEYPAD_TAG).assertIsDisplayed()
        amount.forEach { symbol ->
            val tag = if (symbol == '.') "keypad-dot" else "keypad-$symbol"
            compose.onNodeWithTag(tag).performClick()
        }
        compose.onNodeWithTag(KEYPAD_DONE_TAG).performClick()
    }

    private fun setEntryDate(date: LocalDate) {
        compose.onNodeWithTag(ENTRY_DATE_TAG).performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(setDateOnPicker(date))
        onView(withId(android.R.id.button1)).perform(click())
        compose.onNodeWithTag(ENTRY_DATE_TAG).assertTextEquals(
            date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")),
        )
    }

    private fun selectStatisticsMonth(date: LocalDate) {
        compose.onNodeWithText("月").performScrollTo().performClick()
        val targetMonth = date.format(DateTimeFormatter.ofPattern("yyyy年M月"))
        repeat(MAX_MONTH_BACK_STEPS + 1) { attempt ->
            compose.waitForIdle()
            if (compose.onAllNodesWithText(targetMonth).fetchSemanticsNodes().isNotEmpty()) {
                return
            }
            if (attempt < MAX_MONTH_BACK_STEPS) {
                compose.onNodeWithContentDescription("上一时间段").performClick()
            }
        }
        throw AssertionError(
            "统计月份未在 $MAX_MONTH_BACK_STEPS 次向前导航内到达 $targetMonth",
        )
    }

    private fun assertMonthlyStatistics(direction: String, amount: String) {
        val list = compose.onNodeWithTag(STATISTICS_LIST_TAG)
        list.performScrollToNode(hasText(direction))
        compose.onNodeWithText(direction).performClick()

        val formatted = "¥$amount"
        waitForCondition("$direction 月统计合计更新为 $formatted") {
            compose.onAllNodes(
                hasTestTag(STATISTICS_TOTAL_TAG) and hasText(formatted),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        list.performScrollToNode(hasTestTag(STATISTICS_TOTAL_TAG))
        compose.onNodeWithTag(STATISTICS_TOTAL_TAG)
            .performScrollTo()
            .assertTextEquals(formatted)

        list.performScrollToNode(hasTestTag("statistics-pie"))
        compose.onNodeWithContentDescription("红包，${amount}元，100.0%")
            .performScrollTo()
            .assertIsDisplayed()

        val rankingRowTag = statisticsCategoryRowTag("红包")
        list.performScrollToNode(hasTestTag(rankingRowTag))
        compose.onNodeWithTag(rankingRowTag)
            .performScrollTo()
            .assert(hasText("红包") and hasText(formatted))
    }

    private fun assertLedgerAmount(amount: String) {
        compose.onNodeWithTag(LEDGER_LIST_TAG).performScrollToNode(hasText(amount))
        compose.onNodeWithText(amount).performScrollTo().assertIsDisplayed()
    }

    private fun waitForTag(tag: String, description: String) {
        waitForCondition(description) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCondition(description: String, condition: () -> Boolean) {
        withTimeoutDiagnostic(description, WAIT_TIMEOUT_MILLIS) {
            compose.waitUntil(timeoutMillis = WAIT_TIMEOUT_MILLIS, condition = condition)
        }
    }

    private fun setDateOnPicker(date: LocalDate): ViewAction = object : ViewAction {
        override fun getConstraints(): Matcher<View> =
            isAssignableFrom(DatePicker::class.java)

        override fun getDescription(): String = "设置生产日期选择器为 $date"

        override fun perform(uiController: UiController, view: View) {
            (view as DatePicker).updateDate(date.year, date.monthValue - 1, date.dayOfMonth)
            uiController.loopMainThreadUntilIdle()
        }
    }

    private companion object {
        const val WAIT_TIMEOUT_MILLIS = 5_000L
        const val MAX_MONTH_BACK_STEPS = 1
    }
}
