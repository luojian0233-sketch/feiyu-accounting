package com.local.autobookkeeping.data.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MigrationOrderTest {
    @Test
    fun `migration normalizes ids with gaps to unique continuous zero based order`() {
        assertEquals(
            mapOf(2L to 0, 7L to 1, 19L to 2),
            normalizedDisplayOrders(listOf(19, 2, 7)),
        )
    }

    @Test
    fun `next display order follows maximum rather than row count`() {
        assertEquals(8, nextDisplayOrder(listOf(0, 2, 7)))
        assertEquals(0, nextDisplayOrder(emptyList()))
    }
}
