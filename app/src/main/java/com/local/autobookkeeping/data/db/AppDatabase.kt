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

        private val BUILT_IN_CATEGORIES = listOf(
            "Food",
            "Transport",
            "Shopping",
            "Entertainment",
            "Housing",
            "Utilities",
            "Healthcare",
            "Education",
            "Salary",
            "Other",
        )

        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).addCallback(CategorySeedCallback).build()
    }

    private object CategorySeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            BUILT_IN_CATEGORIES.forEach { name ->
                db.execSQL("INSERT OR IGNORE INTO categories (name) VALUES (?)", arrayOf(name))
            }
        }
    }
}
