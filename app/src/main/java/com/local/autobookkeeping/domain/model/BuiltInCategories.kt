package com.local.autobookkeeping.domain.model

/** 肥鱼皮肤的彩蛋分类，图标是立绘里裁下来的头像（`R.drawable.category_whale_feed`）。 */
const val CATEGORY_WHALE_FEED = "鲸鱼饲料"

private val EXPENSE_BUILT_IN_CATEGORY_NAMES = listOf(
    "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果",
    "服饰", "娱乐", "美容", "通讯", "医疗", "学习", "游戏", "住房",
    "礼品", "宠物", CATEGORY_WHALE_FEED, "汽车", "数码", "办公", "运动", "捐赠", "彩票",
    "红包", "礼金", "转账", "其他",
)

private val INCOME_BUILT_IN_CATEGORY_NAMES = listOf(
    "红包", "工资", "奖金", "理财", "分红", "礼金", "退款", "转账", "其他",
)

internal val BUILT_IN_CATEGORY_NAMES =
    (EXPENSE_BUILT_IN_CATEGORY_NAMES + INCOME_BUILT_IN_CATEGORY_NAMES).distinct()

/**
 * 老库升级时要补进去的新内置分类。
 *
 * [AppDatabase] 的 `onCreate` 只在新装时跑，已经存在的库拿不到新增分类，
 * 所以每次打开再按这张清单幂等插一遍。只列新增项、不是整份内置清单：
 * 否则用户删掉的分类会在每次启动时复活。
 */
internal val BUILT_IN_CATEGORY_NAMES_ADDED_AFTER_SEED = listOf(CATEGORY_WHALE_FEED)

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
