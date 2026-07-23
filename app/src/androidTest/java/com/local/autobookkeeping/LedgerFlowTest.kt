package com.local.autobookkeeping

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createEditAndDeleteExpense() {
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithTag("amount").performTextInput("28.00")
        compose.onNodeWithText("餐饮").performClick()
        compose.onNodeWithTag("merchant").performTextInput("任务六测试面馆")
        compose.onNodeWithText("保存记录").performClick()

        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithText("任务六测试面馆").assertIsDisplayed().performClick()
        compose.onNodeWithTag("merchant").performTextClearance()
        compose.onNodeWithTag("merchant").performTextInput("任务六测试面馆已改")
        compose.onNodeWithText("保存修改").performClick()
        compose.onNodeWithText("任务六测试面馆已改").assertIsDisplayed().performClick()
        compose.onNodeWithText("删除记录").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.onAllNodesWithText("任务六测试面馆已改").assertCountEquals(0)
    }
}
