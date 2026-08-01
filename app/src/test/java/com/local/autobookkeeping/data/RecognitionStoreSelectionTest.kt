package com.local.autobookkeeping.data

import com.local.autobookkeeping.domain.model.Direction
import com.local.autobookkeeping.domain.model.Transaction
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecognitionStoreSelectionTest {
    @Test
    fun singleRefundOriginCandidateIsSelected() {
        val only = transaction(1)

        assertEquals(only, selectUniqueRefundOrigin(listOf(only)))
    }

    @Test
    fun multipleRefundOriginCandidatesAreRejectedAsAmbiguous() {
        assertNull(selectUniqueRefundOrigin(listOf(transaction(1), transaction(2))))
    }

    private fun transaction(id: Long) = Transaction(
        id = id,
        amountMinor = 2_800,
        direction = Direction.EXPENSE,
        category = "Food",
        occurredAt = Instant.parse("2026-07-23T04:00:00Z"),
    )
}
