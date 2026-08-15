package com.local.autobookkeeping.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Test

class CategorySelectorTest {
    @Test
    fun `default categories use stable icons and custom category uses first visible character`() {
        assertEquals("🍜", categoryIconLabel("餐饮"))
        assertEquals("🚕", categoryIconLabel("交通"))
        assertEquals("自", categoryIconLabel("  自定义  "))
        assertEquals("•••", categoryIconLabel("   "))
    }

    @Test
    fun `search ignores case and preserves current historical choice only`() {
        val choices = listOf(
            EntryChoice("餐饮", historicalOnly = false),
            EntryChoice("Office", historicalOnly = false),
            EntryChoice("旧分类", historicalOnly = true),
        )

        assertEquals(listOf("Office"), filterCategoryChoices(choices, "off").map { it.name })
        assertEquals(listOf("餐饮", "Office", "旧分类"), filterCategoryChoices(choices, "").map { it.name })
    }
}
