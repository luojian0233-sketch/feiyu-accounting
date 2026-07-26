package com.local.autobookkeeping

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.local.autobookkeeping.ui.theme.ThemeStyle
import kotlinx.coroutines.runBlocking
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
        scenario = ActivityScenario.launch(MainActivity::class.java)

        createManualTransaction(amount = "28.00", income = false)
        createManualTransaction(amount = "100.00", income = true)
        createManualTransaction(amount = "500.00", income = false, internalTransfer = true)

        compose.onNodeWithTag("nav-STATISTICS").performClick()
        compose.onNodeWithText("月").performClick()
        compose.onNodeWithText("支出").performClick()
        waitForText("支出合计")
        waitForText("¥28.00", minimumCount = 2)
        compose.onNodeWithContentDescription("红包，28.00元，100.0%").assertIsDisplayed()
        compose.onAllNodesWithText("¥28.00").assertCountEquals(2)
        compose.onAllNodesWithText("¥100.00").assertCountEquals(0)
        compose.onAllNodesWithText("¥500.00").assertCountEquals(0)

        compose.onNodeWithText("收入").performClick()
        waitForText("收入合计")
        waitForText("¥100.00", minimumCount = 2)
        compose.onNodeWithContentDescription("红包，100.00元，100.0%").assertIsDisplayed()
        compose.onAllNodesWithText("¥100.00").assertCountEquals(2)
        compose.onAllNodesWithText("¥28.00").assertCountEquals(0)
        compose.onAllNodesWithText("¥500.00").assertCountEquals(0)

        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithText("小票账本").performClick()
        compose.onNodeWithTag("active-theme-RECEIPT_LEDGER").assertIsDisplayed()
        compose.onNodeWithTag("nav-LEDGER").performClick()
        waitForText("-¥28.00")
        compose.onNodeWithText("-¥28.00").assertIsDisplayed()
        compose.onNodeWithText("+¥100.00").assertIsDisplayed()
        compose.onNodeWithText("-¥500.00").assertIsDisplayed()
    }

    private fun createManualTransaction(
        amount: String,
        income: Boolean,
        internalTransfer: Boolean = false,
    ) {
        compose.onNodeWithContentDescription("记一笔").performClick()
        if (income) {
            compose.onNodeWithText("收入").performClick()
        }
        compose.onNodeWithTag("amount").performTextInput(amount)
        compose.onNodeWithText("红包").performScrollTo().performClick()
        if (internalTransfer) {
            val transferLabel = compose.onNodeWithText("内部转账")
            transferLabel.performScrollTo()
            compose.onNode(isToggleable()).performClick()
        }
        compose.onNodeWithText("保存记录").performScrollTo().performClick()
        waitForTag("screen-账本")
    }

    private fun waitForText(text: String, minimumCount: Int = 1) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().size >= minimumCount
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
