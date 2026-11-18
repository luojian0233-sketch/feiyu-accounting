package com.local.autobookkeeping.ui.theme

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「点一下只循环一次」的时序契约。 */
class FishStickerLoopTest {
    @Test
    fun `one tap plays every frame once and settles on the first frame`() = runTest {
        val played = mutableListOf<Int>()
        playStickerLoop(FishStickerKind.LEDGER) { played += it }

        assertEquals((0 until 24).toList() + 0, played)
        assertEquals(24 * 80L, testScheduler.currentTime)
    }

    @Test
    fun `settings keeps the long frame from the source gif`() = runTest {
        val played = mutableListOf<Int>()
        playStickerLoop(FishStickerKind.SETTINGS) { played += it }

        assertEquals((0 until 22).toList() + 0, played)
        assertEquals(21 * 80L + 240L, testScheduler.currentTime)
    }
}
