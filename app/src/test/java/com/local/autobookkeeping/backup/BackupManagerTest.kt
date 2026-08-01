package com.local.autobookkeeping.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertTrue(json.contains("\"formatVersion\":2"))
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
        val input = encoded(document(formatVersion = 3))

        val result = manager(store).restore(input)

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `version one transaction imports with recognition defaults`() {
        val legacy = document(
            data = completeData().copy(
                transactions = listOf(completeData().transactions.first()),
            ),
            formatVersion = 1,
        )
        val decoded = BackupJsonCodec().decode(encoded(legacy))
        val row = decoded.transactions.single()

        assertEquals("UNKNOWN", row.platform)
        assertEquals("UNKNOWN", row.eventKind)
        assertEquals(100, row.recognitionConfidence)
        assertTrue(decoded.recognitionReceipts.isEmpty())
    }

    @Test
    fun `version two round trips redacted receipts`() {
        val document = document(
            formatVersion = 2,
            data = completeData().copy(
                recognitionReceipts = listOf(sampleReceipt(digest = "a".repeat(64))),
            ),
        )

        val bytes = encodedBytes(document)

        assertEquals(document, BackupJsonCodec().decode(bytes.inputStream()))
        assertFalse(bytes.toString(Charsets.UTF_8).contains("寰俊鏀粯椤甸潰鍘熸枃"))
    }

    @Test
    fun `valid version two data restores recognition metadata and receipts`() = runTest {
        val expected = completeData().copy(
            recognitionReceipts = listOf(sampleReceipt(digest = "b".repeat(64))),
        )
        val store = FakeBackupStore(current = emptyData("CLEAR_MINT"))

        val result = manager(store).restore(encoded(document(expected, formatVersion = 2)))

        assertEquals(RestoreResult.Success(expected.transactions.size), result)
        assertEquals(expected, store.current)
    }

    @Test
    fun `version two rejects unknown receipt members before mutation`() = runTest {
        val store = FakeBackupStore(current = completeData())
        val valid = encodedBytes(
            document(
                completeData().copy(
                    recognitionReceipts = listOf(sampleReceipt(digest = "c".repeat(64))),
                ),
                formatVersion = 2,
            ),
        ).toString(Charsets.UTF_8)
        val unknown = valid.replaceFirst(
            "\"digest\":\"${"c".repeat(64)}\"",
            "\"digest\":\"${"c".repeat(64)}\",\"unknown\":true",
        )

        val result = manager(store).restore(unknown.byteInputStream())

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(completeData(), store.current)
        assertEquals(0, store.roomMutationCount)
    }

    @Test
    fun `version two rejects malformed or dangling recognition receipts before mutation`() = runTest {
        val base = completeData()
        val malformed = listOf(
            base.copy(recognitionReceipts = listOf(sampleReceipt(digest = "UPPERCASE".padEnd(64, 'A')))),
            base.copy(recognitionReceipts = listOf(sampleReceipt(transactionId = 999, digest = "d".repeat(64)))),
        )

        malformed.forEach { data ->
            val store = FakeBackupStore(current = base)

            val result = manager(store).restore(encoded(document(data, formatVersion = 2)))

            assertInstanceOf(RestoreResult.Invalid::class.java, result)
            assertEquals(base, store.current)
            assertEquals(0, store.roomMutationCount)
        }
    }

    @Test
    fun `duplicate top-level member is invalid before mutation`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)
        val valid = encodedBytes(document()).toString(Charsets.UTF_8)
        val duplicateTheme = valid.dropLast(1) + ",\"theme\":\"CLEAR_MINT\"}"

        val result = manager(store).restore(duplicateTheme.byteInputStream())

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
    }

    @Test
    fun `escaped duplicate nested member is invalid before mutation`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)
        val valid = encodedBytes(document()).toString(Charsets.UTF_8)
        val escapedDuplicate = valid.replaceFirst(
            "\"amountMinor\":2688",
            "\"amountMinor\":2688,\"\\u0061mountMinor\":1",
        )

        val result = manager(store).restore(escapedDuplicate.byteInputStream())

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
    }

    @Test
    fun `normal escaped string values remain valid`() = runTest {
        val store = FakeBackupStore(current = emptyData("CLEAR_MINT"))
        val valid = encodedBytes(document()).toString(Charsets.UTF_8)
        val escapedValue = valid.replaceFirst(
            "\"note\":\"周末采购\"",
            "\"note\":\"escaped \\\\ slash and \\\" quote\"",
        )

        val result = manager(store).restore(escapedValue.byteInputStream())

        assertEquals(RestoreResult.Success(2), result)
        assertEquals("escaped \\ slash and \" quote", store.current.transactions.first().note)
    }

    @Test
    fun `malformed raw UTF-8 is invalid before mutation`() = runTest {
        val existing = completeData()
        val store = FakeBackupStore(current = existing)
        val valid = encodedBytes(document())
        val malformed = valid.replaceBytes(
            needle = "餐饮".toByteArray(Charsets.UTF_8),
            replacement = byteArrayOf(0xC3.toByte(), 0x28),
        )

        val result = manager(store).restore(malformed.inputStream())

        assertInstanceOf(RestoreResult.Invalid::class.java, result)
        assertEquals(existing, store.current)
        assertEquals(0, store.roomMutationCount)
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

    @Test
    fun `cancellation before Room commit does not run compensation`() {
        val safety = completeData()
        val store = FakeBackupStore(
            current = safety,
            roomFailureBeforeMutation = CancellationException("cancel before commit"),
        )

        assertThrows(CancellationException::class.java) {
            runTest {
                manager(store).restore(
                    openInput = { encoded(document(emptyData("PAPER_MINIMAL"))) },
                )
            }
        }
        assertEquals(safety, store.current)
        assertEquals(1, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `dispatcher handoff cancellation after both mutations compensates and is rethrown`() {
        val callerDispatcher = QueueDispatcher()
        val ioDispatcher = QueueDispatcher()
        val safety = completeData()
        val replacement = emptyData("PAPER_MINIMAL")
        val store = FakeBackupStore(current = safety)

        runBlocking {
            val restore = async(callerDispatcher) {
                manager(store, ioDispatcher).restore(encoded(document(replacement)))
            }
            callerDispatcher.runAll()
            ioDispatcher.runAll()
            assertEquals(replacement, store.current)
            assertEquals(1, store.roomMutationCount)
            assertEquals(1, store.themeMutationCount)

            restore.cancel(CancellationException("cancel return handoff"))
            callerDispatcher.runAll()
            val failure = runCatching { restore.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        }
        assertEquals(safety, store.current)
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    @Test
    fun `production input coordination compensates when final handoff is cancelled`() {
        val callerDispatcher = QueueDispatcher()
        val ioDispatcher = QueueDispatcher()
        val safety = completeData()
        val replacement = emptyData("PAPER_MINIMAL")
        val store = FakeBackupStore(current = safety)
        var openCount = 0
        var closeCount = 0
        val input = object : ByteArrayInputStream(encodedBytes(document(replacement))) {
            override fun close() {
                closeCount += 1
                super.close()
            }
        }

        runBlocking {
            val restore = async(callerDispatcher) {
                manager(store, ioDispatcher).restore(
                    openInput = {
                        openCount += 1
                        input
                    },
                )
            }
            callerDispatcher.runAll()
            ioDispatcher.runAll()
            assertEquals(replacement, store.current)
            assertEquals(1, openCount)
            assertEquals(1, closeCount)

            restore.cancel(CancellationException("cancel production return handoff"))
            callerDispatcher.runAll()
            val failure = runCatching { restore.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
        }
        assertEquals(safety, store.current)
        assertEquals(1, closeCount)
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    @Test
    fun `handoff cancellation keeps rollback failure as suppressed diagnostic`() {
        val callerDispatcher = QueueDispatcher()
        val ioDispatcher = QueueDispatcher()
        val compensationFailure = IOException("private rollback diagnostic")
        val store = FakeBackupStore(
            current = completeData(),
            roomCompensationFailure = compensationFailure,
        )

        runBlocking {
            val restore = async(callerDispatcher) {
                manager(store, ioDispatcher).restore(
                    encoded(document(emptyData("PAPER_MINIMAL"))),
                )
            }
            callerDispatcher.runAll()
            ioDispatcher.runAll()

            val cancellation = CancellationException("cancel return handoff")
            restore.cancel(cancellation)
            callerDispatcher.runAll()
            val failure = runCatching { restore.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(cancellation.suppressed.contains(compensationFailure))
        }
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    @Test
    fun `production handoff cancellation keeps completed rollback failure diagnostics`() {
        val callerDispatcher = QueueDispatcher()
        val ioDispatcher = QueueDispatcher()
        val originalFailure = IOException("private original failure")
        val compensationFailure = IOException("private compensation failure")
        val store = FakeBackupStore(
            current = completeData(),
            themeFailureAfterMutation = originalFailure,
            roomCompensationFailure = compensationFailure,
        )
        val input = encoded(document(emptyData("PAPER_MINIMAL")))

        runBlocking {
            val restore = async(callerDispatcher) {
                manager(store, ioDispatcher).restore(openInput = { input })
            }
            callerDispatcher.runAll()
            ioDispatcher.runAll()

            val cancellation = CancellationException("cancel rollback result handoff")
            restore.cancel(cancellation)
            callerDispatcher.runAll()
            val failure = runCatching { restore.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue(cancellation.suppressed.contains(originalFailure))
            assertTrue(originalFailure.suppressed.contains(compensationFailure))
        }
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    @Test
    fun `input close failure after completed restore preserves the restore result`() = runTest {
        val replacement = emptyData("PAPER_MINIMAL")
        val store = FakeBackupStore(current = completeData())
        val input = object : ByteArrayInputStream(encodedBytes(document(replacement))) {
            override fun close() {
                super.close()
                throw IOException("provider close failed")
            }
        }

        val result = manager(store).restore(openInput = { input })

        assertEquals(RestoreResult.Success(0), result)
        assertEquals(replacement, store.current)
        assertEquals(1, store.roomMutationCount)
        assertEquals(1, store.themeMutationCount)
    }

    @Test
    fun `input close cancellation is rethrown after compensating completed restore`() {
        val safety = completeData()
        val store = FakeBackupStore(current = safety)
        val input = object : ByteArrayInputStream(
            encodedBytes(document(emptyData("PAPER_MINIMAL"))),
        ) {
            override fun close() {
                super.close()
                throw CancellationException("cancel while closing input")
            }
        }

        assertThrows(CancellationException::class.java) {
            runTest { manager(store).restore(openInput = { input }) }
        }
        assertEquals(safety, store.current)
        assertEquals(2, store.roomMutationCount)
        assertEquals(2, store.themeMutationCount)
    }

    @Test
    fun `operation failure stays primary when input close also fails`() = runTest {
        val operationFailure = IOException("database snapshot failed")
        val closeFailure = IOException("provider close failed")
        val store = FakeBackupStore(
            current = completeData(),
            readFailure = operationFailure,
        )
        val input = object : ByteArrayInputStream(encodedBytes(document())) {
            override fun close() {
                super.close()
                throw closeFailure
            }
        }

        val result = manager(store).restore(openInput = { input })

        val failed = assertInstanceOf(RestoreResult.Failed::class.java, result)
        assertEquals(operationFailure, failed.cause)
        assertTrue(operationFailure.suppressed.contains(closeFailure))
        assertEquals(0, store.roomMutationCount)
        assertEquals(0, store.themeMutationCount)
    }

    @Test
    fun `Room compensation failure returns distinct diagnostic result`() = runTest {
        val originalFailure = IOException("original restore failure")
        val compensationFailure = IOException("room rollback failure")
        val store = FakeBackupStore(
            current = completeData(),
            themeFailureAfterMutation = originalFailure,
            roomCompensationFailure = compensationFailure,
        )

        val result = manager(store).restore(encoded(document(emptyData("PAPER_MINIMAL"))))

        val rollbackFailed = assertInstanceOf(RestoreResult.RollbackFailed::class.java, result)
        assertEquals(originalFailure, rollbackFailed.cause)
        assertTrue(originalFailure.suppressed.contains(compensationFailure))
    }

    @Test
    fun `theme compensation failure returns distinct diagnostic result`() = runTest {
        val originalFailure = IOException("original restore failure")
        val compensationFailure = IOException("theme rollback failure")
        val store = FakeBackupStore(
            current = completeData(),
            themeFailureAfterMutation = originalFailure,
            themeCompensationFailure = compensationFailure,
        )

        val result = manager(store).restore(encoded(document(emptyData("PAPER_MINIMAL"))))

        val rollbackFailed = assertInstanceOf(RestoreResult.RollbackFailed::class.java, result)
        assertEquals(originalFailure, rollbackFailed.cause)
        assertTrue(originalFailure.suppressed.contains(compensationFailure))
    }

    private fun manager(
        store: BackupStore,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
    ) = BackupManager(
        store = store,
        codec = BackupJsonCodec(),
        currentTimeMillis = { 1_785_038_400_000 },
        ioDispatcher = dispatcher,
    )

    private fun encoded(document: BackupDocument) =
        encodedBytes(document)
            .inputStream()

    private fun encodedBytes(document: BackupDocument) =
        ByteArrayOutputStream().also { BackupJsonCodec().encode(document, it) }.toByteArray()

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
        recognitionReceipts = data.recognitionReceipts,
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

    private fun sampleReceipt(
        transactionId: Long = 40,
        digest: String,
    ) = BackupRecognitionReceipt(
        transactionId = transactionId,
        channel = "notification",
        platform = "WECHAT",
        eventKind = "PAYMENT",
        observedAtEpochMillis = 1_785_013_199_000,
        digest = digest,
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

    private fun ByteArray.replaceBytes(needle: ByteArray, replacement: ByteArray): ByteArray {
        val index = indices.firstOrNull { start ->
            start + needle.size <= size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        } ?: error("Needle not found")
        return copyOfRange(0, index) + replacement + copyOfRange(index + needle.size, size)
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private class FakeBackupStore(
        var current: BackupData,
        private var readFailure: Throwable? = null,
        private var roomFailureBeforeMutation: Throwable? = null,
        private var roomFailureAfterMutation: Throwable? = null,
        private var themeFailureAfterMutation: Throwable? = null,
        private var roomCompensationFailure: Throwable? = null,
        private var themeCompensationFailure: Throwable? = null,
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
            replaceRoomData(data) {}
        }

        override suspend fun replaceRoomData(
            data: BackupData,
            onCommitted: () -> Unit,
        ) {
            roomMutationCount += 1
            if (roomMutationCount == 1) {
                roomFailureBeforeMutation?.let { failure ->
                    roomFailureBeforeMutation = null
                    throw failure
                }
            }
            if (roomMutationCount > 1) {
                roomCompensationFailure?.let { failure ->
                    roomCompensationFailure = null
                    throw failure
                }
            }
            current = data.copy(theme = current.theme)
            onCommitted()
            roomFailureAfterMutation?.let { failure ->
                roomFailureAfterMutation = null
                throw failure
            }
        }

        override suspend fun replaceTheme(theme: String) {
            themeMutationCount += 1
            if (themeMutationCount > 1) {
                themeCompensationFailure?.let { failure ->
                    themeCompensationFailure = null
                    throw failure
                }
            }
            current = current.copy(theme = theme)
            themeFailureAfterMutation?.let { failure ->
                themeFailureAfterMutation = null
                throw failure
            }
        }
    }
}
