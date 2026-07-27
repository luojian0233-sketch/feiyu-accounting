package com.local.autobookkeeping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ThemeSwitchTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var application: ThemeSwitchTestApplication
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var transactionId: Long = 0

    @Before
    fun seedRealRoomLedger() {
        application = ApplicationProvider.getApplicationContext()
        transactionId = runBlocking {
            application.applicationGraph.ledgerRepository.save(
                Transaction(
                    amountMinor = 2_600,
                    direction = Direction.EXPENSE,
                    category = "餐饮",
                    merchant = TEST_MERCHANT,
                    occurredAt = Instant.parse("2026-07-26T04:00:00Z"),
                ),
            )
        }
    }

    @After
    fun closeScenario() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun productionThemeAndRoomLedgerPersistAcrossMainActivityRecreation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithTag("nav-LEDGER").performClick()
        waitForText(TEST_MERCHANT)
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithText("纸感极简").performClick()
        compose.onNodeWithTag("active-theme-PAPER_MINIMAL").assertIsDisplayed()
        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithTag("screen-账单").assertIsDisplayed()
        compose.onNodeWithText(TEST_MERCHANT).assertIsDisplayed()

        scenario.recreate()

        compose.onNodeWithTag("screen-账单").assertIsDisplayed()
        compose.onNodeWithTag("active-theme-PAPER_MINIMAL").assertIsDisplayed()
        waitForText(TEST_MERCHANT)
        val persisted = runBlocking {
            application.applicationGraph.ledgerRepository.observeTransactions().first()
        }
        assertEquals(transactionId, persisted.single().id)
        assertEquals(TEST_MERCHANT, persisted.single().merchant)
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithTag("theme-PAPER_MINIMAL").assertIsSelected()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TEST_MERCHANT = "主题重建测试交易"
    }
}
