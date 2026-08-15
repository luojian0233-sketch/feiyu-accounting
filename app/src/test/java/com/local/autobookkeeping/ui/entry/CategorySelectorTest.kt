package com.local.autobookkeeping.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySelectorTest {
    @Test
    fun `default choices match database built in category union`() {
        val expectedNames = listOf(
            "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果",
            "服饰", "娱乐", "美容", "通讯", "医疗", "学习", "游戏", "住房",
            "礼品", "宠物", "汽车", "数码", "办公", "运动", "捐赠", "彩票",
            "红包", "礼金", "转账", "其他", "工资", "奖金", "理财", "分红", "退款",
        )

        assertEquals(expectedNames, defaultEntryCategoryChoices().map { it.name })
        assertEquals(
            List(expectedNames.size) { false },
            defaultEntryCategoryChoices().map { it.historicalOnly },
        )
    }

    @Test
    fun `every database built in category uses its stable icon`() {
        val expectedIcons = linkedMapOf(
            "餐饮" to "🍜", "交通" to "🚕", "日用" to "🧴", "购物" to "🛍",
            "零食" to "🍦", "饮品" to "🥤", "蔬菜" to "🥬", "水果" to "🍓",
            "服饰" to "👕", "娱乐" to "🎵", "美容" to "💄", "通讯" to "☎",
            "医疗" to "💊", "学习" to "🎓", "游戏" to "🎮", "住房" to "🏠",
            "礼品" to "🎁", "宠物" to "🐾", "汽车" to "🚗", "数码" to "📷",
            "办公" to "💼", "运动" to "🏓", "捐赠" to "❤", "彩票" to "🎫",
            "红包" to "🧧", "礼金" to "✉", "转账" to "⇄", "其他" to "•••",
            "工资" to "💰", "奖金" to "🏆", "理财" to "📈", "分红" to "💹",
            "退款" to "↩",
        )

        expectedIcons.forEach { (name, icon) ->
            assertEquals("stable icon for $name", icon, categoryIconLabel(name))
        }
    }

    @Test
    fun `custom category uses first visible character with generic blank fallback`() {
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

    @Test
    fun `historical choice is separated from available choices and follows search`() {
        val choices = listOf(
            EntryChoice("餐饮", historicalOnly = false),
            EntryChoice("Office", historicalOnly = false),
            EntryChoice("旧分类", historicalOnly = true),
        )

        val unfiltered = categoryChoiceSections(choices, "")
        assertEquals("旧分类", unfiltered.historicalChoice?.name)
        assertEquals(listOf("餐饮", "Office"), unfiltered.availableChoices.map { it.name })

        val availableMatch = categoryChoiceSections(choices, "off")
        assertNull(availableMatch.historicalChoice)
        assertEquals(listOf("Office"), availableMatch.availableChoices.map { it.name })

        val historicalMatch = categoryChoiceSections(choices, "旧")
        assertEquals("旧分类", historicalMatch.historicalChoice?.name)
        assertEquals(emptyList<EntryChoice>(), historicalMatch.availableChoices)

        val noMatch = categoryChoiceSections(choices, "missing")
        assertNull(noMatch.historicalChoice)
        assertEquals(emptyList<EntryChoice>(), noMatch.availableChoices)
    }
}
