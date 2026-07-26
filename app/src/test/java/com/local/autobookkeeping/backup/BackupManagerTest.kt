package com.local.autobookkeeping.backup

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupManagerTest {
    @Test
    fun `export and restore round trip preserves the complete approved backup dataset`() = runTest {
        val source = FakeBackupStore(current = completeData())
        val output = ByteArrayOutputStream()
        val manager = manager(source)

        manager.export(output)

        val restored = FakeBackupStore(current = emptyData(theme = "CLEAR_MINT"))
        val result = manager(restored).restore(output.toByteArray().inputStream())

        assertEquals(RestoreResult.Success(transactionCount = 2), result)
        assertEquals(completeData(), restored.current)
        val json = output.toString(Charsets.UTF_8.name())
        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"exportedAtEpochMillis\":1785038400000"))
        assertTrue(!json.contains("notificationText"))
        assertTrue(!json.contains("wechatEnabled"))
        assertTrue(!json.contains("alipayEnabled"))
        assertTrue(!json.contains("enabledBankPackages"))
    }

    @Test
    fun `invalid JSON never mutates current data`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)

        val result = manager(store).restore("not-json".byteInputStream())

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `unsupported version never mutates current data`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)
        val input = encoded(document(formatVersion = 2))

        val result = manager(store).restore(input)

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `broken references never mutate current data`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)
        val broken = document().let { backup ->
            backup.copy(
                transactions = backup.transactions.map { row ->
                    if (row.id == 40L) row.copy(categoryId = 999L) else row
                },
            )
        }

        val result = manager(store).restore(encoded(broken))

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `non-positive amount never mutates current data`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)
        val invalid = document().let { backup ->
            backup.copy(
                transactions = backup.transactions.mapIndexed { index, row ->
                    if (index == 0) row.copy(amountMinor = 0) else row
                },
            )
        }

        val result = manager(store).restore(encoded(invalid))

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `every malformed enum timestamp duplicate and reference is rejected before mutation`() = runTest {
        val base = document()
        val malformed = linkedMapOf(
            "direction" to base.copy(
                transactions = base.transactions.replaceFirst { it.copy(direction = "SIDEWAYS") },
            ),
            "source" to base.copy(
                transactions = base.transactions.replaceFirst { it.copy(source = "EMAIL") },
            ),
            "currency" to base.copy(
                accounts = base.accounts.map { it.copy(currency = "CN") },
            ),
            "timestamp" to base.copy(exportedAtEpochMillis = 0),
            "transaction timestamp" to base.copy(
                transactions = base.transactions.replaceFirst {
                    it.copy(occurredAtEpochMillis = 0)
                },
            ),
            "timestamp order" to base.copy(
                transactions = base.transactions.replaceFirst {
                    it.copy(updatedAtEpochMillis = it.createdAtEpochMillis - 1)
                },
            ),
            "duplicate transaction id" to base.copy(
                transactions = base.transactions + base.transactions.first().copy(dedupeKey = "third"),
            ),
            "duplicate category id" to base.copy(
                categories = base.categories + base.categories.first().copy(name = "交通"),
            ),
            "duplicate account id" to base.copy(
                accounts = base.accounts + base.accounts.first().copy(name = "银行卡"),
            ),
            "duplicate merchant rule id" to base.copy(
                merchantRules = base.merchantRules + base.merchantRules.first().copy(
                    merchantPattern = "另一商户",
                ),
            ),
            "duplicate category name" to base.copy(
                categories = base.categories + base.categories.first().copy(id = 11),
            ),
            "duplicate account name" to base.copy(
                accounts = base.accounts + base.accounts.first().copy(id = 21),
            ),
            "duplicate merchant pattern" to base.copy(
                merchantRules = base.merchantRules + base.merchantRules.first().copy(id = 31),
            ),
            "duplicate dedupe key" to base.copy(
                transactions = base.transactions.map { it.copy(dedupeKey = "same-key") },
            ),
            "missing transaction account" to base.copy(
                transactions = base.transactions.replaceFirst { it.copy(accountId = 999) },
            ),
            "missing merchant category" to base.copy(
                merchantRules = base.merchantRules.map { it.copy(categoryId = 999) },
            ),
            "missing merchant account" to base.copy(
                merchantRules = base.merchantRules.map { it.copy(accountId = 999) },
            ),
            "missing refund" to base.copy(
                transactions = base.transactions.map { row ->
                    if (row.id == 41L) row.copy(refundOfId = 999) else row
                },
            ),
            "self refund" to base.copy(
                transactions = base.transactions.map { row ->
                    if (row.id == 41L) row.copy(refundOfId = 41) else row
                },
            ),
            "invalid theme" to base.copy(theme = "NEON"),
        )

        malformed.forEach { (label, backup) ->
            val existing = completeData()
            val store = FakeBackupStore(current = existing)

            val result = manager(store).restore(encoded(backup))

            assertInstanceOf(
                RestoreResult.Invalid::class.java,
                result,
                "$label should be invalid",
            )
            assertEquals(existing, store.current, "$label mutated current data")
            assertEquals(0, store.roomMutationCount, "$label replaced Room data")
            assertEquals(0, store.themeMutationCount, "$label replaced the theme")
        }
    }

    @Test
    fun `restore failure after Room mutation compensates both Room data and theme`() = runTest {
        val safety = completeData()
        val replacement = emptyData(theme = "PAPER_MINIMAL")
        val store = FakeBackupStore(
            current = safety,
            themeFailureAfterMutation = IOException("disk full"),
        )

        val result = manager(store).restore(encoded(document(replacement)))

        assertEquals(RestoreResult.Failed("恢复失败，当前数据已保留"), result)
        assertEquals(safety, store.current)
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    @Test
    fun `Room replacement failure after mutation is compensated`() = runTest {
        val safety = completeData()
        val store = FakeBackupStore(
            current = safety,
            roomFailureAfterMutation = IOException("database write failed"),
        )

        val result = manager(store).restore(encoded(document(emptyData("PAPER_MINIMAL"))))

        assertEquals(RestoreResult.Failed("恢复失败，当前数据已保留"), result)
        assertEquals(safety, store.current)
        assertEquals(2, store.roomMutationCount)
    }

    @Test
    fun `export failure is propagated before output is written`() {
        val output = ByteArrayOutputStream()
        val store = FakeBackupStore(
            current = completeData(),
            readFailure = IOException("database read failed"),
        )

        assertThrows(IOException::class.java) {
            runTest { manager(store).export(output) }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `export cancellation is propagated`() {
        val store = FakeBackupStore(
            current = completeData(),
            readFailure = CancellationException("cancel export"),
        )

        assertThrows(CancellationException::class.java) {
            runTest { manager(store).export(ByteArrayOutputStream()) }
        }
    }

    @Test
    fun `restore cancellation after mutation compensates and is rethrown`() {
        val safety = completeData()
        val store = FakeBackupStore(
            current = safety,
            themeFailureAfterMutation = CancellationException("cancel restore"),
        )

        assertThrows(CancellationException::class.java) {
            runTest {
                manager(store).restore(encoded(document(emptyData("PAPER_MINIMAL"))))
            }
        }
        assertEquals(safety, store.current)
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    private fun manager(store: BackupStore) = BackupManager(
        store = store,
        codec = BackupJsonCodec(),
        currentTimeMillis = { 1_785_038_400_000 },
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun encoded(document: BackupDocument) =
        ByteArrayOutputStream().also { BackupJsonCodec().encode(document, it) }
            .toByteArray()
            .inputStream()

    private fun document(
        data: BackupData = completeData(),
        formatVersion: Int = 1,
    ) = BackupDocument(
        formatVersion = formatVersion,
        exportedAtEpochMillis = 1_785_038_400_000,
        transactions = data.transactions,
        categories = data.categories,
        accounts = data.accounts,
        merchantRules = data.merchantRules,
        theme = data.theme,
    )

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
            ),
        ),
        categories = listOf(
            BackupCategory(id = 10, name = "餐饮", displayOrder = 2, hidden = true),
        ),
        accounts = listOf(
            BackupAccount(
                id = 20,
                name = "现金",
                currency = "CNY",
                displayOrder = 3,
                hidden = true,
            ),
        ),
        merchantRules = listOf(
            BackupMerchantRule(
                id = 30,
                merchantPattern = "本地超市",
                categoryId = 10,
                accountId = 20,
                priority = 9,
            ),
        ),
        theme = "RECEIPT_LEDGER",
    )

    private fun emptyData(theme: String) = BackupData(
        transactions = emptyList(),
        categories = listOf(
            BackupCategory(id = 1, name = "其他", displayOrder = 0, hidden = false),
        ),
        accounts = emptyList(),
        merchantRules = emptyList(),
        theme = theme,
    )

    private fun <T> List<T>.replaceFirst(transform: (T) -> T): List<T> =
        mapIndexed { index, value -> if (index == 0) transform(value) else value }

    private class FakeBackupStore(
        var current: BackupData,
        private var readFailure: Throwable? = null,
        private var roomFailureAfterMutation: Throwable? = null,
        private var themeFailureAfterMutation: Throwable? = null,
    ) : BackupStore {
        var roomMutationCount = 0
        var themeMutationCount = 0

        override suspend fun read(): BackupData {
            readFailure?.let { failure ->
                readFailure = null
                throw failure
            }
            return current
        }

        override suspend fun replaceRoomData(data: BackupData) {
            roomMutationCount += 1
            current = data.copy(theme = current.theme)
            roomFailureAfterMutation?.let { failure ->
                roomFailureAfterMutation = null
                throw failure
            }
        }

        override suspend fun replaceTheme(theme: String) {
            themeMutationCount += 1
            current = current.copy(theme = theme)
            themeFailureAfterMutation?.let { failure ->
                themeFailureAfterMutation = null
                throw failure
            }
        }
    }
}
