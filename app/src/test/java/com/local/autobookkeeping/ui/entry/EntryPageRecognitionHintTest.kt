package com.local.autobookkeeping.ui.entry

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.local.autobookkeeping.domain.model.CATEGORY_UNSURE
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.robolectric.annotation.Config

/**
 * 微信支付通知里没有商户名，规则学不到东西。页面识别是唯一能把「哪家店」读出来的口子，
 * 所以「待分类 + 商户为空」时必须给一个能点的入口，其它情况不打扰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h792dp-xhdpi")
class EntryPageRecognitionHintTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `unsure category without merchant offers the recognition shortcut`() {
        var opened = 0
        compose.setContent {
            EntryScreen(
                state = EntryUiState(
                    draft = EntryDraft(
                        amountText = "4.00",
                        category = CATEGORY_UNSURE,
                        merchant = "",
                    ),
                ),
                onUpdate = {},
                onOpenCategorySelector = {},
                onSave = {},
                onRequestDelete = {},
                onConfirmDelete = {},
                onDismissDelete = {},
                onBack = {},
                onOpenPageRecognition = { opened += 1 },
            )
        }

        compose.onNodeWithText("开启页面识别后，打开微信账单详情能自动读到是哪家店。")
            .assertIsDisplayed()
        compose.onNodeWithTag(ENTRY_PAGE_RECOGNITION_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(1, opened)
    }

    @Test
    fun `known merchant hides the shortcut`() {
        compose.setContent {
            EntryScreen(
                state = EntryUiState(
                    draft = EntryDraft(
                        amountText = "4.00",
                        category = CATEGORY_UNSURE,
                        merchant = "瑞幸咖啡",
                    ),
                ),
                onUpdate = {},
                onOpenCategorySelector = {},
                onSave = {},
                onRequestDelete = {},
                onConfirmDelete = {},
                onDismissDelete = {},
                onBack = {},
            )
        }

        compose.onNodeWithTag(ENTRY_PAGE_RECOGNITION_TAG).assertDoesNotExist()
    }

    @Test
    fun `settled category hides the shortcut`() {
        compose.setContent {
            EntryScreen(
                state = EntryUiState(
                    draft = EntryDraft(amountText = "4.00", category = "餐饮", merchant = ""),
                ),
                onUpdate = {},
                onOpenCategorySelector = {},
                onSave = {},
                onRequestDelete = {},
                onConfirmDelete = {},
                onDismissDelete = {},
                onBack = {},
            )
        }

        compose.onNodeWithTag(ENTRY_PAGE_RECOGNITION_TAG).assertDoesNotExist()
    }
}
