package com.local.autobookkeeping

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import com.local.autobookkeeping.ui.entry.CategorySelectorScreen
import com.local.autobookkeeping.ui.entry.EntryChoice
import com.local.autobookkeeping.ui.theme.AutoBookkeepingTheme
import com.local.autobookkeeping.ui.theme.ThemeStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class EntryCategorySelectorScreenTest(
    private val theme: ThemeStyle,
) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun categoryGridSearchSelectionAndBackAreAccessible() {
        var selected: String? = null
        var backCalls = 0
        compose.setContent {
            AutoBookkeepingTheme(theme) {
                CategorySelectorScreen(
                    choices = listOf(
                        EntryChoice("餐饮", false),
                        EntryChoice("交通", false),
                        EntryChoice("旧分类", true),
                    ),
                    selectedCategory = "餐饮",
                    loading = false,
                    onSelect = { selected = it },
                    onBack = { backCalls += 1 },
                )
            }
        }

        compose.onNodeWithText("选择分类").assertIsDisplayed()
        compose.onNodeWithTag("category-grid").assertIsDisplayed()
        compose.onNodeWithTag("category-choice-餐饮").assertIsSelected()
        compose.onNodeWithTag("category-search").performTextInput("交通")
        compose.onNodeWithTag("category-choice-交通").performClick()
        assertEquals("交通", selected)
        compose.onNodeWithContentDescription("返回编辑记录").performClick()
        pressBack()
        assertEquals(2, backCalls)
    }

    @Test
    fun searchWithoutMatchesShowsFixedEmptyMessage() {
        compose.setContent {
            AutoBookkeepingTheme(theme) {
                CategorySelectorScreen(
                    choices = listOf(EntryChoice("餐饮", false)),
                    selectedCategory = "餐饮",
                    loading = false,
                    onSelect = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("category-search").performTextInput("不存在")
        compose.onNodeWithText("没有匹配的分类").assertIsDisplayed()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "theme={0}")
        fun themes(): List<Array<Any>> = ThemeStyle.entries.map { arrayOf<Any>(it) }
    }
}
