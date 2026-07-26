package com.local.autobookkeeping

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.data.SettingsRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.home.NotificationPermissionProvider
import com.local.autobookkeeping.ui.theme.ThemeStyle
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class ThemeSwitchTest {
    @get:Rule
    val compose = createAndroidComposeRule<ThemeSwitchTestActivity>()

    @Test
    fun productionThemePersistsAcrossRecreationWithoutLosingNavigationOrLedger() {
        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithText("主题重建测试交易").assertIsDisplayed()
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithText("纸感极简").performClick()
        compose.onNodeWithTag("nav-STATISTICS").performClick()
        compose.onNodeWithTag("active-theme-PAPER_MINIMAL").assertIsDisplayed()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("screen-统计").assertIsDisplayed()
        compose.onNodeWithTag("active-theme-PAPER_MINIMAL").assertIsDisplayed()
        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithText("主题重建测试交易").assertIsDisplayed()
        compose.onNodeWithTag("nav-SETTINGS").performClick()
        compose.onNodeWithTag("theme-PAPER_MINIMAL").assertIsSelected()
    }
}

class ThemeSwitchTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository(themeDataStore())
        setContent {
            AutoBookkeepingRoot(
                repository = ThemeLedgerRepository,
                notificationPermission = NotificationPermissionProvider { false },
                settingsRepository = settings,
            )
        }
    }

    private fun themeDataStore(): DataStore<Preferences> = synchronized(ThemeSwitchTestActivity::class.java) {
        sharedDataStore ?: PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { preferencesDataStoreFile("theme-switch-test") },
        ).also { sharedDataStore = it }
    }

    companion object {
        private var sharedDataStore: DataStore<Preferences>? = null
    }
}

private object ThemeLedgerRepository : LedgerRepository {
    private val rows = MutableStateFlow(
        listOf(
            Transaction(
                id = 8801,
                amountMinor = 2_600,
                direction = Direction.EXPENSE,
                category = "餐饮",
                merchant = "主题重建测试交易",
                occurredAt = Instant.parse("2026-07-26T04:00:00Z"),
            ),
        ),
    )

    override fun observeTransactions(): Flow<List<Transaction>> = rows
    override suspend fun save(transaction: Transaction): Long = transaction.id
    override suspend fun delete(id: Long) = Unit
    override suspend fun findByDedupeKey(key: String): Transaction? = null
    override suspend fun replaceAllInTransaction(transactions: List<Transaction>) {
        rows.value = transactions
    }
}
