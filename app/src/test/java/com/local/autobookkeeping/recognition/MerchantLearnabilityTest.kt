package com.local.autobookkeeping.recognition

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「商户名」的可信度闸门。
 *
 * 背景：待核对记录被手动确认时会把商户名写成规则，下次同商户直接归到那一类。
 * 如果商户名其实是「支付成功」「1」这类抽取残渣，规则一旦写下，以后每一笔同款通知
 * 都会被静默归类，而且因为有了分类就不再进待核对——错一次就永远错下去。
 */
class MerchantLearnabilityTest {

    @Test
    fun `payment status fragments are never treated as merchants`() {
        val fragments = listOf(
            "支付成功",
            "付款成功",
            "扣款成功",
            "已支付",
            "支付成功-美团订单",
            "微信支付",
            "支付宝",
            "云闪付",
            "花呗",
            "交易单号",
        )

        fragments.forEach { assertFalse(isLearnableMerchant(it), "$it 不该被学习") }
    }

    @Test
    fun `placeholders and junk values are rejected`() {
        val junk = listOf("其他", "待分类", "待确认", "未知", "1", "2026", "···", "  ", "a", "")

        junk.forEach { assertFalse(isLearnableMerchant(it), "$it 不该被学习") }
    }

    @Test
    fun `real merchant names still pass`() {
        val names = listOf(
            "星巴克",
            "美团外卖",
            "肯德基(中关村店)",
            "7-11",
            "中国电信",
            "盒马鲜生·望京店",
            "Apple Store",
        )

        names.forEach { assertTrue(isLearnableMerchant(it), "$it 应该被学习") }
    }

    @Test
    fun `null and blank are rejected`() {
        assertFalse(isLearnableMerchant(null))
        assertFalse(isLearnableMerchant("   "))
    }

    @Test
    fun `overlong values are rejected so a whole sentence cannot become a rule`() {
        assertFalse(isLearnableMerchant("北京" + "某".repeat(40) + "科技有限公司第一分公司"))
    }
}
