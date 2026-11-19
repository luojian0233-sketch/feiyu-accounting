package com.local.autobookkeeping.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogContractTest {
    @Test
    fun `fatty fish skin is appended without disturbing existing themes`() {
        assertEquals(
            listOf("CLEAR_MINT", "RECEIPT_LEDGER", "PAPER_MINIMAL", "FATTY_FISH"),
            ThemeStyle.entries.map { it.name },
        )
    }

    @Test
    fun `default theme is fatty fish`() {
        assertEquals(ThemeStyle.FATTY_FISH, DEFAULT_THEME_STYLE)
    }

    @Test
    fun `fatty fish is a named backup value`() {
        assertTrue(ThemeStyle.entries.any { it.name == "FATTY_FISH" })
        assertEquals(ThemeStyle.FATTY_FISH, ThemeStyle.valueOf("FATTY_FISH"))
    }
}
