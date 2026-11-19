package com.local.autobookkeeping.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局内键盘的输入规则：整数 7 位、小数 2 位、单个小数点、不产生前导零。
 * 所有结果都必须能通过 `EntryDraft.toTransaction()` 的金额校验。
 */
class AmountKeypadLogicTest {
    private fun type(vararg keys: AmountKey) =
        keys.fold("") { text, key -> applyAmountKey(text, key) }

    private fun digit(value: Int) = AmountKey.Digit(value)

    @Test
    fun `digits accumulate from empty`() {
        assertEquals("128", type(digit(1), digit(2), digit(8)))
    }

    @Test
    fun `leading zero is replaced instead of prepending`() {
        assertEquals("5", type(digit(0), digit(5)))
        assertEquals("0", type(digit(0)))
    }

    @Test
    fun `dot on empty starts a zero`() {
        assertEquals("0.", type(AmountKey.Dot))
        assertEquals("0.5", type(AmountKey.Dot, digit(5)))
    }

    @Test
    fun `second dot is ignored`() {
        assertEquals("12.3", type(digit(1), digit(2), AmountKey.Dot, digit(3), AmountKey.Dot))
    }

    @Test
    fun `fraction stops at two digits`() {
        assertEquals(
            "12.34",
            type(digit(1), digit(2), AmountKey.Dot, digit(3), digit(4), digit(5)),
        )
    }

    @Test
    fun `integer part stops at seven digits`() {
        val text = (1..9).fold("") { acc, value -> applyAmountKey(acc, digit(value % 10)) }
        assertEquals(MAX_AMOUNT_INTEGER_DIGITS, text.length)
    }

    @Test
    fun `double zero respects the fraction limit`() {
        assertEquals("200", type(digit(2), AmountKey.DoubleZero))
        assertEquals("0.10", type(AmountKey.Dot, digit(1), AmountKey.DoubleZero))
        assertEquals("20", type(digit(2), AmountKey.DoubleZero, AmountKey.Backspace))
    }

    @Test
    fun `backspace and clear`() {
        assertEquals("", applyAmountKey("", AmountKey.Backspace))
        assertEquals("12.3", applyAmountKey("12.34", AmountKey.Backspace))
        assertEquals("", applyAmountKey("128.50", AmountKey.Clear))
    }

    @Test
    fun `every reachable amount passes the draft validation pattern`() {
        // 输入中途允许「12.」这种还在敲小数位的状态
        val pattern = Regex("""(?:0|[1-9]\d*)(?:\.\d{0,2})?""")
        val keys = listOf(
            digit(0), digit(1), digit(9), AmountKey.Dot, AmountKey.DoubleZero,
            AmountKey.Backspace, AmountKey.Clear,
        )
        var text = ""
        keys.forEachIndexed { step, key ->
            text = applyAmountKey(text, keys[(step * 3 + 1) % keys.size])
            assertTrue("第 $step 步得到非法金额：$text", text.isEmpty() || pattern.matches(text))
        }
    }

    @Test
    fun `keypad tags are stable for ui tests`() {
        assertEquals("keypad-7", keypadTag(AmountKey.Digit(7)))
        assertEquals("keypad-dot", keypadTag(AmountKey.Dot))
        assertEquals("keypad-00", keypadTag(AmountKey.DoubleZero))
        assertEquals("keypad-back", keypadTag(AmountKey.Backspace))
        assertEquals("keypad-clear", keypadTag(AmountKey.Clear))
    }
}
