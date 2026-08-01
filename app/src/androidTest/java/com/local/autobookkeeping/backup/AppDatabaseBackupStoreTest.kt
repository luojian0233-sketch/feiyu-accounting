package com.local.autobookkeeping.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.local.autobookkeeping.data.SettingsRepository
import com.local.autobookkeeping.data.db.AppDatabase
import com.local.autobookkeeping.ui.theme.ThemeStyle
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppDatabaseBackupStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferencesFile = File(
        context.cacheDir,
        "backup-store-${UUID.randomUUID()}.preferences_pb",
    )
    private lateinit var database: AppDatabase
    private lateinit var store: AppDatabaseBackupStore

    @Before
    fun createStore() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { preferencesFile },
        )
        store = AppDatabaseBackupStore(
            database = database,
            settings = SettingsRepository(dataStore, database),
        )
    }

    @After
    fun closeStore() {
        database.close()
        scope.cancel()
        preferencesFile.delete()
    }

    @Test
    fun completeDatasetReplacesRoomRowsAndThemeWithIdsAndReferencesPreserved() = runBlocking {
        val expected = completeData()

        store.replaceRoomData(expected)
        store.replaceTheme(expected.theme)

        assertEquals(expected, store.read())
    }

    @Test
    fun failedForeignKeyReplacementRollsBackOriginalRoomSnapshot() = runBlocking {
        val safety = completeData()
        store.replaceRoomData(safety)
        store.replaceTheme(safety.theme)
        val invalid = safety.copy(
            merchantRules = safety.merchantRules.map { it.copy(categoryId = 999) },
        )

        val failed = runCatching { store.replaceRoomData(invalid) }.isFailure

        assertTrue(failed)
        assertEquals(safety, store.read())
    }

    @Test
    fun failedReceiptInsertionRollsBackTransactionsAndReceiptsInTheSameRoomTransaction() = runBlocking {
        val safety = completeData()
        store.replaceRoomData(safety)
        store.replaceTheme(safety.theme)
        val invalid = safety.copy(
            recognitionReceipts = listOf(
                BackupRecognitionReceipt(
                    transactionId = 999,
                    channel = "notification",
                    platform = "WECHAT",
                    eventKind = "PAYMENT",
                    observedAtEpochMillis = 1_785_013_199_000,
                    digest = "e".repeat(64),
                ),
            ),
        )

        val failed = runCatching { store.replaceRoomData(invalid) }.isFailure

        assertTrue(failed)
        assertEquals(safety, store.read())
    }

    private fun completeData() = BackupData(
        transactions = listOf(
            BackupTransaction(
                id = 40,
                amountMinor = 2_688,
                currency = "CNY",
                direction = "EXPENSE",
                categoryId = 10,
                occurredAtEpochMillis = 1_785_013_200_000,
                merchant = "本地超市",
                note = "周末采购",
                accountId = 20,
                source = "NOTIFICATION",
                pendingConfirmation = true,
                internalTransfer = false,
                dedupeKey = "digest-40",
                redactedNotificationDigest = "sha256-redacted-40",
                refundOfId = null,
                createdAtEpochMillis = 1_785_013_201_000,
                updatedAtEpochMillis = 1_785_013_202_000,
                platform = "WECHAT",
                eventKind = "PAYMENT",
                recognitionConfidence = 88,
            ),
            BackupTransaction(
                id = 41,
                amountMinor = 500,
                currency = "CNY",
                direction = "INCOME",
                categoryId = 10,
                occurredAtEpochMillis = 1_785_016_800_000,
                merchant = "本地超市",
                note = "退款",
                accountId = 20,
                source = "MANUAL",
                pendingConfirmation = false,
                internalTransfer = true,
                dedupeKey = "digest-41",
                redactedNotificationDigest = null,
                refundOfId = 40,
                createdAtEpochMillis = 1_785_016_801_000,
                updatedAtEpochMillis = 1_785_016_802_000,
                platform = "ALIPAY",
                eventKind = "REFUND",
                recognitionConfidence = 91,
            ),
        ),
        categories = listOf(
            BackupCategory(10, "餐饮", displayOrder = 2, hidden = true),
        ),
        accounts = listOf(
            BackupAccount(20, "现金", "CNY", displayOrder = 3, hidden = true),
        ),
        merchantRules = listOf(
            BackupMerchantRule(30, "本地超市", categoryId = 10, accountId = 20, priority = 9),
        ),
        recognitionReceipts = listOf(
            BackupRecognitionReceipt(
                transactionId = 40,
                channel = "notification",
                platform = "WECHAT",
                eventKind = "PAYMENT",
                observedAtEpochMillis = 1_785_013_199_000,
                digest = "f".repeat(64),
            ),
        ),
        theme = ThemeStyle.RECEIPT_LEDGER.name,
    )
}
