package com.local.autobookkeeping

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.local.autobookkeeping.data.SettingsRepository
import com.local.autobookkeeping.data.db.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class ThemeSwitchTestApplication : AutoBookkeepingApplication() {
    private val runId = UUID.randomUUID().toString()
    private val databaseName = "theme-switch-$runId.db"
    private val preferencesName = "theme-switch-$runId"
    private val dataStoreJob = SupervisorJob()
    private val dataStoreScope = CoroutineScope(dataStoreJob + Dispatchers.IO)
    private val databaseDelegate = lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .addCallback(AppDatabase.categorySeedCallback())
            .build()
    }
    private val dataStoreDelegate = lazy {
        PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { preferencesFile },
        )
    }

    val testDatabase: AppDatabase
        get() = databaseDelegate.value

    val testDataStore: DataStore<Preferences>
        get() = dataStoreDelegate.value

    private val preferencesFile: File
        get() = preferencesDataStoreFile(preferencesName)

    override fun createApplicationGraph(): ApplicationGraph = ApplicationGraph(
        application = this,
        databaseProvider = { testDatabase },
        settingsRepositoryProvider = { _, database ->
            SettingsRepository(testDataStore, database)
        },
    )

    fun cleanUpTestStorage() {
        runBlocking { dataStoreJob.cancelAndJoin() }
        if (databaseDelegate.isInitialized()) {
            databaseDelegate.value.close()
        }
        deleteDatabase(databaseName)
        preferencesFile.delete()
    }
}
