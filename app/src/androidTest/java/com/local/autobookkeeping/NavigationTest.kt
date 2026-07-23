package com.local.autobookkeeping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.autobookkeeping.ui.navigation.AppDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensEveryBottomDestination() {
        listOf(
            AppDestination.HOME,
            AppDestination.LEDGER,
            AppDestination.STATISTICS,
            AppDestination.SETTINGS,
        ).forEach { destination ->
            compose.onNodeWithTag("nav-${destination.name}").performClick()
            compose.onNodeWithTag("screen-${destination.label}").assertIsDisplayed()
        }
        compose.onNodeWithTag("nav-${AppDestination.ENTRY.name}").performClick()
        compose.onNodeWithTag("screen-${AppDestination.ENTRY.label}").assertIsDisplayed()
    }

    @Test
    fun systemBackFromEntryReturnsHomeInsteadOfFinishingActivity() {
        compose.onNodeWithTag("nav-${AppDestination.ENTRY.name}").performClick()

        compose.activity.onBackPressedDispatcher.onBackPressed()

        compose.onNodeWithTag("screen-${AppDestination.HOME.label}").assertIsDisplayed()
    }
}
