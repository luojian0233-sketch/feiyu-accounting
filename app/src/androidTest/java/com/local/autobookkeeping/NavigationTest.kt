package com.local.autobookkeeping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            "\u68E3\u682D\u3009",
            "\u7490\uFE40\u5D1F",
            "\u7F01\u71BB\uE178",
            "\u7471\u5267\u7586",
        ).forEach { label ->
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithTag("screen-$label").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("\u7471\u9881\u7AF4\u7ED7?").performClick()
        compose.onNodeWithTag("screen-\u7471\u9881\u7AF4\u7ED7?").assertIsDisplayed()
    }
}
