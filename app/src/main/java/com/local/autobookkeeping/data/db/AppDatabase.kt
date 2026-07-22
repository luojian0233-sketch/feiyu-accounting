package com.local.autobookkeeping.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        MerchantRuleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao

    companion object {
        private const val DATABASE_NAME = "autobookkeeping.db"

        private val EXPENSE_BUILT_IN_CATEGORIES = listOf(
            "餐饮", "交通", "日用", "购物", "零食", "饮品", "蔬菜", "水果", "服饰", "娱乐", "美容", "通讯",
            "医疗", "学习", "游戏", "住房", "礼品", "宠物", "汽车", "数码", "办公", "运动", "捐赠", "彩票",
            "红包", "礼金", "转账", "其他",
        )

        private val INCOME_BUILT_IN_CATEGORIES = listOf(
            "红包", "工资", "奖金", "理财", "分红", "礼金", "退款", "转账", "其他",
        )

        private val BUILT_IN_CATEGORIES = (EXPENSE_BUILT_IN_CATEGORIES + INCOME_BUILT_IN_CATEGORIES).distinct()

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).addCallback(CategorySeedCallback).build()

        fun categorySeedCallback(): Callback = CategorySeedCallback
    }

    private object CategorySeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            BUILT_IN_CATEGORIES.forEach { name ->
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES (?)", arrayOf(name))
            }
        }
    }
}
