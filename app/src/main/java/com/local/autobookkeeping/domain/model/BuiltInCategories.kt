package com.local.autobookkeeping.domain.model

private val EXPENSE_BUILT_IN_CATEGORY_NAMES = listOf(
    "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果",
    "服饰", "娱乐", "美容", "通讯", "医疗", "学习", "游戏", "住房",
    "礼品", "宠物", "汽车", "数码", "办公", "运动", "捐赠", "彩票",
    "红包", "礼金", "转账", "其他",
)

private val INCOME_BUILT_IN_CATEGORY_NAMES = listOf(
    "红包", "工资", "奖金", "理财", "分红", "礼金", "退款", "转账", "其他",
)

internal val BUILT_IN_CATEGORY_NAMES =
    (EXPENSE_BUILT_IN_CATEGORY_NAMES + INCOME_BUILT_IN_CATEGORY_NAMES).distinct()

/**
 * 金额和方向都已经判准、但分类还判不出来的记录先落在这里。
 *
 * 微信/支付宝的支付通知只给「已支付¥4.00」这一行，商户名系统层面就没有，
 * 靠通知永远推不出分类。与其把这些记录挂在「待核对」里挡住统计，
 * 不如照常入账、只把分类留成待补——账目始终是准的，分类可以慢慢补。
 */
const val CATEGORY_UNSURE = "待分类"

/** 老版本用的名字，只用来在数据库打开时把存量记录迁到 [CATEGORY_UNSURE]。 */
internal const val LEGACY_PENDING_CATEGORY = "待确认"
