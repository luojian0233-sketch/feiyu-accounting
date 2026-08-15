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
