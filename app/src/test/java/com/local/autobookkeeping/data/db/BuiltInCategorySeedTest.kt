package com.local.autobookkeeping.data.db

import androidx.room.Room
import com.local.autobookkeeping.domain.model.CATEGORY_WHALE_FEED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 新增的内置分类要能补进已经存在的库。
 *
 * `onCreate` 只在新装时跑，老用户升级上来的时候分类表已经建好了，
 * 所以靠 `onOpen` 里的幂等插入补齐，并且重复打开不能造出重复行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuiltInCategorySeedTest {
    @Test
    fun `built in category added after seed lands in existing database once`() =
        runBlocking(Dispatchers.IO) {
            val app = RuntimeEnvironment.getApplication()
            val dbName = "built-in-seed.db"
            app.deleteDatabase(dbName)

            // 模拟一份老库：只有用户已有的两条分类，没有新内置分类
            val legacy = Room.databaseBuilder(app, AppDatabase::class.java, dbName).build()
            try {
                legacy.transactionDao().insertAllCategories(
                    listOf(CategoryEntity(name = "餐饮"), CategoryEntity(name = "我的分类")),
                )
            } finally {
                legacy.close()
            }

            val db = Room.databaseBuilder(app, AppDatabase::class.java, dbName)
                .addCallback(AppDatabase.categorySeedCallback())
                .build()
            try {
                val names = db.transactionDao().allCategories().map { it.name }
                assertEquals("新分类只该插一条", 1, names.count { it == CATEGORY_WHALE_FEED })
                assertTrue("原有分类不能被动", "餐饮" in names)
                assertTrue("用户自建分类不能被动", "我的分类" in names)
            } finally {
                db.close()
            }

            // 每次启动都会再走一遍 onOpen，必须幂等
            val again = Room.databaseBuilder(app, AppDatabase::class.java, dbName)
                .addCallback(AppDatabase.categorySeedCallback())
                .build()
            try {
                assertEquals(
                    "重复打开不该多造分类",
                    1,
                    again.transactionDao().allCategories().count { it.name == CATEGORY_WHALE_FEED },
                )
            } finally {
                again.close()
            }
            app.deleteDatabase(dbName)
            Unit
        }
}
