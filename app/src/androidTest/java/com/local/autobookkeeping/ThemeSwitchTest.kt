package com.local.autobookkeeping

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
import org.junit.Rule
import org.junit.Test

class ThemeSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchThemeImmediatelyWithoutProductionData() {
        var style by mutableStateOf(ThemeStyle.CLEAR_MINT)
        compose.setContent {
            AutoBookkeepingTheme(style) {
                Column(Modifier.testTag("theme-${style.name}")) {
                    Text("当前页面")
                    Button(onClick = { style = ThemeStyle.PAPER_MINIMAL }) {
                        Text("纸感极简")
                    }
                }
            }
        }

        compose.onNodeWithText("纸感极简").performClick()

        compose.onNodeWithText("当前页面").assertIsDisplayed()
        compose.onNodeWithText("纸感极简").assertIsDisplayed()
        compose.onNodeWithTag("theme-PAPER_MINIMAL").assertIsDisplayed()
    }
}
