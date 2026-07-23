package com.local.autobookkeeping

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.local.autobookkeeping.data.LedgerRepository
import com.local.autobookkeeping.domain.model.Transaction
import com.local.autobookkeeping.ui.home.NotificationPermissionProvider
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

    private class InMemoryLedgerRepository : LedgerRepository {
        private val mutableRows = MutableStateFlow<List<Transaction>>(emptyList())
        private var nextId = 1L

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
