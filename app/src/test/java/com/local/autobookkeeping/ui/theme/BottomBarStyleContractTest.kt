package com.local.autobookkeeping.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BottomBarStyleContractTest {
    @Test
    fun `each decorated skin maps to its own bottom bar language`() {
        assertEquals(BottomBarStyle.MINT_PILL, bottomBarStyleFor(HeaderDecor.MINT_SPROUT))
        assertEquals(BottomBarStyle.RECEIPT_TICKET, bottomBarStyleFor(HeaderDecor.RECEIPT_STUB))
        assertEquals(BottomBarStyle.PAPER_LINE, bottomBarStyleFor(HeaderDecor.PAPER_RULE))
    }

    @Test
    fun `first three skins stay visually distinct`() {
        val styles = listOf(
            bottomBarStyleFor(HeaderDecor.MINT_SPROUT),
            bottomBarStyleFor(HeaderDecor.RECEIPT_STUB),
            bottomBarStyleFor(HeaderDecor.PAPER_RULE),
        )
        assertEquals(styles.size, styles.toSet().size)
        assertNotEquals(styles[0], styles[1])
        assertNotEquals(styles[1], styles[2])
    }

    @Test
    fun `fatty fish gets its own sticker bar`() {
        assertEquals(BottomBarStyle.OCEAN_STICKER, bottomBarStyleFor(HeaderDecor.OCEAN_WAVE))
    }

    @Test
    fun `all four skins use four different bar languages`() {
        val styles = HeaderDecor.entries.map { bottomBarStyleFor(it) }
        assertEquals(styles.size, styles.toSet().size)
    }
}
