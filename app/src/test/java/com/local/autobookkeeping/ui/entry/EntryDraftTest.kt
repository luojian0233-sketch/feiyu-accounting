package com.local.autobookkeeping.ui.entry

import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Source
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EntryDraftTest {
    @Test
    fun `amount is converted to exact minor units without floating point`() {
        val transaction = draft("28.05").toTransaction()

        assertEquals(2_805L, transaction.amountMinor)
    }

    @Test
    fun `whole yuan and one decimal are accepted`() {
        assertEquals(2_800L, draft("28").toTransaction().amountMinor)
        assertEquals(2_850L, draft("28.5").toTransaction().amountMinor)
    }

    @Test
    fun `signs scientific notation excess scale and zero are rejected`() {
        listOf("-1", "+1", "1e2", "1.001", "0", "0.00", ".").forEach { input ->
            val failure = assertThrows(EntryValidationException::class.java) {
                draft(input).toTransaction()
            }
            assertEquals("请输入大于 0 且最多两位小数的金额", failure.message)
        }
    }

    @Test
    fun `minor unit overflow is rejected`() {
        assertThrows(EntryValidationException::class.java) {
            draft("92233720368547758.08").toTransaction()
        }
    }

    @Test
    fun `category is independent from direction`() {
        val income = draft("10", Direction.INCOME, "红包").toTransaction()
        val expense = draft("10", Direction.EXPENSE, "红包").toTransaction()

        assertEquals(Direction.INCOME, income.direction)
        assertEquals(Direction.EXPENSE, expense.direction)
        assertEquals("红包", income.category)
        assertEquals("红包", expense.category)
    }

    @Test
    fun `pending imported row stays pending without explicit confirmation`() {
        val draft = EntryDraft.from(
            draft("12").toTransaction().copy(
                id = 8,
                source = Source.NOTIFICATION,
                pendingConfirmation = true,
                merchant = "便利店",
            ),
        )

        assertTrue(draft.toTransaction(confirmPending = false).pendingConfirmation)
        assertFalse(draft.toTransaction(confirmPending = true).pendingConfirmation)
    }

    private fun draft(
        amount: String,
        direction: Direction = Direction.EXPENSE,
        category: String = "餐饮",
    ) = EntryDraft(
        amountText = amount,
        direction = direction,
        category = category,
        occurredAt = Instant.parse("2026-07-22T04:00:00Z"),
    )
}
