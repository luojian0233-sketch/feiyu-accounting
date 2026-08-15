package com.local.autobookkeeping

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.home.NotificationPermissionProvider
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test

class LedgerFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun createEditAndDeleteExpenseInIsolatedRepository() {
        val repository = InMemoryLedgerRepository()
        compose.setContent {
            AutoBookkeepingRoot(
                repository = repository,
                notificationPermission = NotificationPermissionProvider { false },
            )
        }

        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithTag("amount").performTextInput("28.00")
        compose.onNodeWithTag("entry-category-field").performClick()
        compose.onNodeWithTag("category-choice-餐饮").performClick()
        compose.onNodeWithTag("merchant").performTextInput("任务六测试面馆")
        compose.onNodeWithText("保存记录").performClick()

        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithText("任务六测试面馆").assertIsDisplayed().performClick()
        compose.onNodeWithTag("merchant").performTextClearance()
        compose.onNodeWithTag("merchant").performTextInput("任务六测试面馆已改")
        compose.onNodeWithText("保存修改").performClick()
        compose.onNodeWithText("任务六测试面馆已改").assertIsDisplayed().performClick()
        compose.onNodeWithTag("entry-list")
            .performScrollToNode(hasText("删除记录"))
        compose.onNodeWithText("删除记录").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.onAllNodesWithText("任务六测试面馆已改").assertCountEquals(0)
    }

    @Test
    fun visibleEntryBackReturnsToPreviousDestination() {
        compose.setContent {
            AutoBookkeepingRoot(
                repository = InMemoryLedgerRepository(),
                notificationPermission = NotificationPermissionProvider { false },
            )
        }
        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithContentDescription("记一笔").performClick()

        compose.onNodeWithTag("entry-back").performClick()

        compose.onNodeWithTag("screen-账单").assertIsDisplayed()
    }

    @Test
    fun categoryPickerTopAndSystemBackPreserveCompleteDraft() {
        val repository = InMemoryLedgerRepository(
            initialRows = listOf(
                Transaction(
                    id = 7L,
                    amountMinor = 2_800,
                    direction = Direction.INCOME,
                    category = "工资",
                    occurredAt = Instant.parse("2026-08-15T06:35:00Z"),
                    merchant = "商户草稿值",
                    account = "账户草稿值",
                    note = "备注草稿值",
                    internalTransfer = true,
                    source = Source.PAGE_RECOGNITION,
                    pendingConfirmation = true,
                ),
            ),
        )
        compose.setContent {
            AutoBookkeepingRoot(
                repository = repository,
                notificationPermission = NotificationPermissionProvider { false },
            )
        }

        fun openCategoryPicker() {
            compose.onNodeWithTag("entry-list")
                .performScrollToNode(hasTestTag("entry-category-field"))
            compose.onNodeWithTag("entry-category-field").performClick()
            compose.onNodeWithTag("category-grid").assertIsDisplayed()
        }

        fun assertCompleteDraft() {
            val entryList = compose.onNodeWithTag("entry-list")
            entryList.performScrollToNode(hasText("收入"))
            compose.onNodeWithText("收入").assertIsSelected()
            entryList.performScrollToNode(hasTestTag("amount"))
            compose.onNodeWithTag("amount").assertTextContains("28.00")
            entryList.performScrollToNode(hasTestTag("entry-category-field"))
            compose.onNodeWithTag("entry-category-field").assertTextContains("工资")

            listOf("商户草稿值", "账户草稿值", "备注草稿值").forEach { value ->
                val textField = hasSetTextAction() and hasText(value)
                entryList.performScrollToNode(textField)
                compose.onNode(textField).assertIsDisplayed()
            }

            entryList.performScrollToNode(hasText("2026年8月15日"))
            compose.onNodeWithText("2026年8月15日").assertIsDisplayed()
            compose.onNodeWithText("14:35").assertIsDisplayed()
            entryList.performScrollToNode(hasText("内部转账"))
            compose.onNode(isToggleable()).assertIsOn()
            entryList.performScrollToNode(
                hasText("这是一笔自动识别记录，请核对方向、分类和商户后再确认。"),
            )
            compose.onNodeWithText("这是一笔自动识别记录，请核对方向、分类和商户后再确认。")
                .assertIsDisplayed()
        }

        compose.onNodeWithTag("nav-LEDGER").performClick()
        compose.onNodeWithText("商户草稿值").performClick()

        openCategoryPicker()
        compose.onNodeWithContentDescription("返回编辑记录").performClick()
        assertCompleteDraft()

        openCategoryPicker()
        pressBack()
        assertCompleteDraft()
    }

    private class InMemoryLedgerRepository(
        initialRows: List<Transaction> = emptyList(),
    ) : LedgerRepository {
        private val mutableRows = MutableStateFlow(initialRows)
        private var nextId = (initialRows.maxOfOrNull { it.id } ?: 0L) + 1

        override fun observeTransactions(): Flow<List<Transaction>> = mutableRows.asStateFlow()

        override suspend fun save(transaction: Transaction): Long {
            val id = if (transaction.id == 0L) nextId++ else transaction.id
            mutableRows.value = mutableRows.value.filterNot { it.id == id } + transaction.copy(id = id)
            return id
        }

        override suspend fun delete(id: Long) {
            mutableRows.value = mutableRows.value.filterNot { it.id == id }
        }

        override suspend fun findByDedupeKey(key: String): Transaction? =
            mutableRows.value.firstOrNull { it.dedupeKey == key }

        override suspend fun replaceAllInTransaction(transactions: List<Transaction>) {
            mutableRows.value = transactions
            nextId = (transactions.maxOfOrNull { it.id } ?: 0L) + 1
        }
    }
}
