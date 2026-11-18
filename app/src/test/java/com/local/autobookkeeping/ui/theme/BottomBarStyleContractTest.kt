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
    fun `fatty fish keeps the classic bar until assets are ready`() {
        assertEquals(BottomBarStyle.PENDING_ASSETS, bottomBarStyleFor(HeaderDecor.OCEAN_WAVE))
    }
}
