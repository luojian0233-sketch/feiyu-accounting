# WeChat, Alipay Automatic and Screenshot Bookkeeping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build reliable WeChat and Alipay income/expense recognition plus a Quick Settings “截屏记账” action, with local OCR, confirmation for uncertainty, learned merchant categories, and cross-channel deduplication.

**Architecture:** Notification, accessibility-page, and user-triggered screenshot capture adapters all emit the same immutable `RecognitionCandidate`. A pure parser and `RecognitionCoordinator` normalize, classify, match, and deduplicate candidates before atomically writing transactions and redacted receipts to Room. Compose settings and review screens expose permission recovery, platform switches, candidate selection, and prefilled confirmation without storing screenshots or raw page text.

**Tech Stack:** Kotlin 2.3.10, Android SDK 26–37, Jetpack Compose/Material 3, Room 2.8.4, DataStore 1.2.1, coroutines 1.10.2, ML Kit bundled Chinese text recognition 16.0.1, JUnit 5, Robolectric, AndroidX instrumentation tests.

## Global Constraints

- Keep `minSdk = 26`, `targetSdk = 37`, and Java 17.
- Main acceptance device is PKG110, Android 16 / API 36, ADB serial `81c74ce1`.
- Every explicit ADB command must be run with a 10-second host timeout; never restart ADB and never ask to unplug the phone.
- Install with `adb -s 81c74ce1 install -r ...`; never clear data or uninstall the current app.
- Automatic accessibility events are limited to `com.tencent.mm` and `com.eg.android.AlipayGphone`.
- A user-triggered Quick Settings capture may inspect the currently visible app once; it must not enable continuous capture of other apps.
- Screenshots, accessibility node snapshots, and full OCR text remain in memory only and are released immediately after processing.
- Do not add `INTERNET`, broad storage, or overlay permissions.
- Do not invoke click, gesture, text-input, payment, refund, or other UI-control accessibility APIs.
- A high-confidence result is auto-saved only when amount, direction, event type, stable merchant/event identity, and visible category are unambiguous.
- Pending rows are excluded from statistics until confirmed.
- Existing transactions, categories, accounts, merchant rules, settings, themes, backups, and statistics must remain compatible.
- The Room migration must upgrade database version 2 without destructive fallback.
- All feature work follows red-green-refactor TDD with one focused commit per task.
- Official API references: [ML Kit bundled Chinese OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [Accessibility screenshots](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [Quick Settings tiles](https://developer.android.com/reference/android/service/quicksettings/TileService), and [MediaProjection](https://developer.android.com/media/grow/media-projection).

---

## File Structure

New focused units:

- `domain/model/Recognition.kt`: platform, channel, event, candidate, receipt, and coordinator result contracts.
- `recognition/PaymentPageParser.kt`: shared text geometry and parser interfaces.
- `recognition/WechatPageParser.kt`: WeChat page signatures and field extraction.
- `recognition/AlipayPageParser.kt`: Alipay page signatures and field extraction.
- `recognition/GenericBillParser.kt`: explicit screenshot fallback for non-platform bills.
- `recognition/MerchantClassifier.kt`: event defaults, built-in merchants, and user rule precedence.
- `recognition/RecognitionCoordinator.kt`: atomic match, merge, auto-save, and review decision.
- `recognition/RecognitionDiagnostics.kt`: redacted latest status only.
- `recognition/ReviewRequestCodec.kt`: safe intent payload for normalized candidate choices.
- `recognition/capture/LocalTextRecognizer.kt`: OCR interface and ML Kit adapter.
- `recognition/capture/AccessibilityCaptureGateway.kt`: in-process one-shot screenshot broker.
- `recognition/capture/ScreenshotRecognitionController.kt`: screenshot → OCR → parse → coordinator.
- `recognition/accessibility/PaymentPageAccessibilityService.kt`: WeChat/Alipay page observer.
- `recognition/accessibility/AccessibilitySnapshotFactory.kt`: accessibility nodes → geometry-preserving snapshot.
- `recognition/tile/ScreenshotBookkeepingTileService.kt`: Quick Settings entry.
- `recognition/tile/ScreenshotCaptureActivity.kt`: transparent capture/consent activity.
- `recognition/tile/ProjectionCaptureService.kt`: one-frame MediaProjection fallback.
- `ui/settings/AutomaticBookkeepingSettingsScreen.kt`: the two-card settings subpage.
- `ui/recognition/RecognitionReviewScreen.kt`: multi-candidate selection UI.

Existing files are modified only where they own integration: Room entities/DAO/database, repositories, backup codec/store, notification service/parsers, application graph, entry confirmation, settings state, root navigation, manifest, and tests.

---

### Task 1: Recognition Domain Contracts

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/domain/model/Recognition.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/domain/model/Transaction.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/domain/RecognitionModelTest.kt`

**Interfaces:**
- Produces: `PaymentPlatform`, `CaptureChannel`, `TransactionEventKind`, `RecognitionCandidate`, `RecognitionReceipt`, `RecognitionOutcome`, and the new `Transaction` metadata defaults.
- Consumes: existing `Direction` and `Source`.

- [ ] **Step 1: Write the failing domain tests**

```kotlin
class RecognitionModelTest {
    @Test
    fun `candidate rejects missing amount and non sha256 evidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionCandidate(
                platform = PaymentPlatform.WECHAT,
                channel = CaptureChannel.ACCESSIBILITY,
                eventKind = TransactionEventKind.PAYMENT,
                amountMinor = 0,
                direction = Direction.EXPENSE,
                merchant = "中国电信",
                account = "零钱",
                occurredAt = Instant.parse("2026-07-29T10:00:00Z"),
                confidence = 95,
                evidenceDigest = "raw-page-text",
            )
        }
    }

    @Test
    fun `manual transaction keeps backward compatible recognition defaults`() {
        val transaction = Transaction(
            amountMinor = 100,
            direction = Direction.EXPENSE,
            category = "其他",
            occurredAt = Instant.parse("2026-07-29T10:00:00Z"),
        )
        assertEquals(PaymentPlatform.UNKNOWN, transaction.platform)
        assertEquals(TransactionEventKind.UNKNOWN, transaction.eventKind)
        assertEquals(100, transaction.recognitionConfidence)
    }
}
```

- [ ] **Step 2: Run the tests and verify the missing types fail compilation**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.domain.RecognitionModelTest"
```

Expected: `Unresolved reference: RecognitionCandidate`.

- [ ] **Step 3: Add the minimal immutable contracts**

```kotlin
enum class PaymentPlatform { WECHAT, ALIPAY, OTHER, UNKNOWN }
enum class CaptureChannel { NOTIFICATION, ACCESSIBILITY, SCREENSHOT }
enum class TransactionEventKind { PAYMENT, RECEIPT, REFUND, TRANSFER, RED_PACKET, UNKNOWN }
enum class RecognitionRejectReason {
    NOT_PAYMENT_PAGE,
    INCOMPLETE_FIELDS,
    CONFLICTING_DIRECTION,
    SECURE_WINDOW,
    CAPTURE_FAILED,
    OCR_FAILED,
    PERMISSION_MISSING,
}

data class RecognitionCandidate(
    val platform: PaymentPlatform,
    val channel: CaptureChannel,
    val eventKind: TransactionEventKind,
    val amountMinor: Long,
    val direction: Direction?,
    val merchant: String?,
    val account: String?,
    val occurredAt: Instant,
    val confidence: Int,
    val evidenceDigest: String,
) {
    init {
        require(amountMinor > 0)
        require(confidence in 0..100)
        require(evidenceDigest.matches(Regex("[0-9a-f]{64}")))
    }
}

data class RecognitionReceipt(
    val transactionId: Long = 0,
    val channel: CaptureChannel,
    val platform: PaymentPlatform,
    val eventKind: TransactionEventKind,
    val observedAt: Instant,
    val digest: String,
) {
    init {
        require(digest.matches(Regex("[0-9a-f]{64}")))
    }
}

data class RecognitionChoice(
    val candidate: RecognitionCandidate,
    val matchTransactionId: Long? = null,
)

sealed interface RecognitionOutcome {
    data class Saved(val transactionId: Long, val pending: Boolean) : RecognitionOutcome
    data class NeedsSelection(val options: List<RecognitionChoice>) : RecognitionOutcome
    data object Duplicate : RecognitionOutcome
    data class Rejected(val reason: RecognitionRejectReason) : RecognitionOutcome
}
```

Extend `Source` with `PAGE_RECOGNITION` and `SCREENSHOT`, and add to `Transaction`:

```kotlin
val platform: PaymentPlatform = PaymentPlatform.UNKNOWN,
val eventKind: TransactionEventKind = TransactionEventKind.UNKNOWN,
val recognitionConfidence: Int = 100,
```

Validate `recognitionConfidence in 0..100`; update `EntryDraft.from()`/`toTransaction()` so editing preserves the three fields.

- [ ] **Step 4: Run domain and entry tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.domain.RecognitionModelTest" --tests "com.local.autobookkeeping.ui.entry.*"
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/domain app/src/main/java/com/local/autobookkeeping/ui/entry app/src/test/java/com/local/autobookkeeping/domain app/src/test/java/com/local/autobookkeeping/ui/entry
git commit -m "feat: add recognition domain contracts"
```

---

### Task 2: Room Version 3 and Atomic Recognition Store

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/data/db/Entities.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/data/db/TransactionDao.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/data/LedgerRepository.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/data/RecognitionStore.kt`
- Modify: `app/src/androidTest/java/com/local/autobookkeeping/data/db/AppDatabaseMigrationTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/data/RecognitionStoreTest.kt`
- Generate: `app/schemas/com.local.autobookkeeping.data.db.AppDatabase/3.json`

**Interfaces:**
- Consumes: Task 1 recognition contracts.
- Produces: `RecognitionStore`, `RecognitionMatchQuery`, `MerchantRuleMatch`, `MIGRATION_2_3`, atomic receipt insertion, and `LedgerRepository.findById`.

- [ ] **Step 1: Write failing migration and atomicity tests**

```kotlin
@Test
fun migration2To3PreservesRowsAndAddsRecognitionDefaults() = runTest {
    createVersion2Database(name = "recognition-migration.db", transactionId = 7)
    val db = Room.databaseBuilder(context, AppDatabase::class.java, "recognition-migration.db")
        .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        .build()
    val row = db.transactionDao().findById(7)!!
    assertEquals("UNKNOWN", row.platform)
    assertEquals("UNKNOWN", row.eventKind)
    assertEquals(100, row.recognitionConfidence)
    assertTrue(db.transactionDao().allRecognitionReceipts().isEmpty())
}

@Test
fun createRecognizedTransactionAndReceiptIsAtomic() = runTest {
    val id = store.create(sampleRecognizedTransaction(), sampleReceipt())
    assertEquals(id, store.findReceipt(DIGEST)?.transactionId)
    assertFailsWith<DuplicateRecognitionReceiptException> {
        store.create(sampleRecognizedTransaction(), sampleReceipt())
    }
    assertEquals(1, repository.observeTransactions().first().size)
}
```

- [ ] **Step 2: Run instrumentation tests and verify schema/API failures**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.data.db.AppDatabaseMigrationTest,com.local.autobookkeeping.data.RecognitionStoreTest
```

Expected: failures for missing version 3 schema and `RecognitionStore`.

- [ ] **Step 3: Add entities, DAO queries, migration, and store**

Add to `TransactionEntity`:

```kotlin
@ColumnInfo(defaultValue = "'UNKNOWN'") val platform: String = "UNKNOWN",
@ColumnInfo(defaultValue = "'UNKNOWN'") val eventKind: String = "UNKNOWN",
@ColumnInfo(defaultValue = "100") val recognitionConfidence: Int = 100,
```

Add:

```kotlin
@Entity(
    tableName = "recognition_receipts",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transactionId"), Index(value = ["digest"], unique = true)],
)
data class RecognitionReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val channel: String,
    val platform: String,
    val eventKind: String,
    val observedAtEpochMillis: Long,
    val digest: String,
)
```

Set `AppDatabase.version = 3`; create `MIGRATION_2_3` with three `ALTER TABLE` statements, the receipts table, both indices, and no destructive fallback.

Define:

```kotlin
data class RecognitionMatchQuery(
    val platform: PaymentPlatform,
    val direction: Direction,
    val amountMinor: Long,
    val eventKind: TransactionEventKind,
    val from: Instant,
    val to: Instant,
)

data class MerchantRuleMatch(
    val merchantPattern: String,
    val category: String,
    val account: String?,
    val priority: Int,
)

class DuplicateRecognitionReceiptException(
    val digest: String,
    cause: Throwable? = null,
) : RuntimeException("Recognition receipt already exists", cause)

interface MerchantRuleOperations {
    suspend fun rememberMerchantRule(merchant: String, category: String, account: String?)
}

interface RecognitionConfirmationOperations {
    suspend fun confirmAndRemember(transaction: Transaction): Long
}

interface RecognitionStore : MerchantRuleOperations, RecognitionConfirmationOperations {
    suspend fun findReceipt(digest: String): RecognitionReceipt?
    suspend fun findMatches(query: RecognitionMatchQuery): List<Transaction>
    suspend fun create(transaction: Transaction, receipt: RecognitionReceipt): Long
    suspend fun update(transaction: Transaction, receipt: RecognitionReceipt)
    suspend fun attachReceipt(transactionId: Long, receipt: RecognitionReceipt)
    suspend fun findMerchantRule(normalizedMerchant: String): MerchantRuleMatch?
    suspend fun isCategoryVisible(name: String): Boolean
    suspend fun findRefundOrigin(candidate: RecognitionCandidate): Transaction?
}
```

Implement every write with `database.withTransaction`; map new metadata in `RoomLedgerRepository`; add `findById(id: Long): Transaction?`.

`confirmAndRemember(transaction)` must update the pending transaction and upsert its normalized merchant rule in one Room transaction. For a blank merchant it updates only the transaction; it never creates an empty rule.

- [ ] **Step 4: Run Room, repository, and migration tests**

Run:

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.local.autobookkeeping.data
```

Then:

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: all data tests and existing JVM tests pass; schema `3.json` is generated.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/data app/src/androidTest/java/com/local/autobookkeeping/data app/schemas
git commit -m "feat: persist redacted recognition receipts"
```

---

### Task 3: Backward-Compatible Backup Format 2

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/backup/BackupDocument.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/backup/BackupManager.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/backup/BackupManagerTest.kt`
- Modify: `app/src/androidTest/java/com/local/autobookkeeping/backup/AppDatabaseBackupStoreTest.kt`

**Interfaces:**
- Consumes: Task 2 transaction metadata and receipt entity.
- Produces: format version 2 export; format version 1 import defaults; receipt round-trip without raw content.

- [ ] **Step 1: Add failing v1 compatibility and v2 round-trip tests**

```kotlin
@Test
fun `version one transaction imports with recognition defaults`() {
    val decoded = codec.decode(VERSION_ONE_BACKUP.byteInputStream())
    val row = decoded.transactions.single()
    assertEquals("UNKNOWN", row.platform)
    assertEquals("UNKNOWN", row.eventKind)
    assertEquals(100, row.recognitionConfidence)
    assertTrue(decoded.recognitionReceipts.isEmpty())
}

@Test
fun `version two round trips redacted receipts`() {
    val document = sampleDocument(
        formatVersion = 2,
        recognitionReceipts = listOf(sampleReceipt(digest = "a".repeat(64))),
    )
    val bytes = ByteArrayOutputStream().also { codec.encode(document, it) }.toByteArray()
    assertEquals(document, codec.decode(bytes.inputStream()))
    assertFalse(bytes.toString(Charsets.UTF_8).contains("微信支付页面原文"))
}
```

- [ ] **Step 2: Run backup tests and verify missing fields fail**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.backup.*"
```

Expected: failures for missing `recognitionReceipts`, `platform`, and format version handling.

- [ ] **Step 3: Implement format-aware strict decoding**

Add:

```kotlin
data class BackupRecognitionReceipt(
    val transactionId: Long,
    val channel: String,
    val platform: String,
    val eventKind: String,
    val observedAtEpochMillis: Long,
    val digest: String,
)
```

Write `formatVersion = 2`. For version 1, require the exact old key sets and map transaction metadata to `UNKNOWN/UNKNOWN/100`; for version 2, require the exact expanded key sets and validate 64-character lowercase SHA-256 receipt digests. Include receipts in `AppDatabaseBackupStore.read()` and in the same Room transaction used by restore. Validate every receipt references a restored transaction.

- [ ] **Step 4: Run JVM and backup instrumentation tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.backup.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.backup.AppDatabaseBackupStoreTest
```

Expected: both commands pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/backup app/src/test/java/com/local/autobookkeeping/backup app/src/androidTest/java/com/local/autobookkeeping/backup
git commit -m "feat: back up recognition metadata safely"
```

---

### Task 4: Geometry-Preserving WeChat, Alipay, and Generic Parsers

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/PaymentPageParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/WechatPageParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/AlipayPageParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/GenericBillParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/RecognitionDigest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/PaymentPageParserTest.kt`

**Interfaces:**
- Consumes: Task 1 `RecognitionCandidate`.
- Produces: `ObservedTextElement`, `PageSnapshot`, `ExtractionOutcome`, `PaymentPageParser`, and platform-specific parsers usable by accessibility and OCR.

- [ ] **Step 1: Add failing sanitized page fixture tests**

```kotlin
@Test
fun `wechat telecom payment extracts expense merchant account and amount`() {
    val outcome = WechatPageParser.parse(
        page(
            "微信支付", "中国电信", "使用零钱支付", "¥1.00", "账单详情",
            packageName = "com.tencent.mm",
        ),
    ) as ExtractionOutcome.Single
    assertEquals(100, outcome.candidate.amountMinor)
    assertEquals(Direction.EXPENSE, outcome.candidate.direction)
    assertEquals("中国电信", outcome.candidate.merchant)
    assertEquals("零钱", outcome.candidate.account)
    assertEquals(TransactionEventKind.PAYMENT, outcome.candidate.eventKind)
}

@Test
fun `alipay receipt and refund are income`() {
    assertIncome(AlipayPageParser.parse(page("收款到账", "便利店", "¥8.80")))
    assertEvent(
        TransactionEventKind.REFUND,
        AlipayPageParser.parse(page("退款成功", "中国电信", "¥1.00")),
    )
}

@Test
fun `multiple equally ranked bill cards require selection`() {
    val outcome = GenericBillParser.parse(twoCardPage("¥1.00", "¥2.00"))
    assertEquals(2, (outcome as ExtractionOutcome.Multiple).options.size)
}

@Test
fun `visible transaction time is preferred over capture time`() {
    val outcome = WechatPageParser.parse(
        page("微信支付", "中国电信", "¥1.00", "2026-07-29 18:19"),
    ) as ExtractionOutcome.Single
    assertEquals(
        Instant.parse("2026-07-29T10:19:00Z"),
        outcome.candidate.occurredAt,
    )
}

@Test
fun `chat balance asset and coupon screens are rejected`() {
    assertRejected(WechatPageParser.parse(page("聊天", "已支付¥1.00")))
    assertRejected(AlipayPageParser.parse(page("总资产", "余额 ¥10000.00")))
    assertRejected(GenericBillParser.parse(page("优惠券", "最高减 ¥20.00")))
}
```

- [ ] **Step 2: Run parser tests and verify missing parser types fail**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.PaymentPageParserTest"
```

Expected: compilation failure for `WechatPageParser`.

- [ ] **Step 3: Implement the pure parser pipeline**

Use geometry rather than concatenated full text:

```kotlin
data class TextBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class ObservedTextElement(val text: String, val bounds: TextBounds)
data class PageSnapshot(
    val packageName: String?,
    val elements: List<ObservedTextElement>,
    val observedAt: Instant,
)

sealed interface ExtractionOutcome {
    data class Single(val candidate: RecognitionCandidate) : ExtractionOutcome
    data class Multiple(val options: List<RecognitionCandidate>) : ExtractionOutcome
    data class Rejected(val reason: RecognitionRejectReason) : ExtractionOutcome
}

fun interface PaymentPageParser {
    fun parse(snapshot: PageSnapshot): ExtractionOutcome
}
```

Implement exact platform package checks for automatic parsing, page signatures, clause-local currency extraction, top-to-bottom card grouping, account extraction from “使用…支付”, and visible time parsing. Parse `yyyy-MM-dd HH:mm`, `MM-dd HH:mm`, and card-local `HH:mm` in `BOOKKEEPING_ZONE`; use `snapshot.observedAt` only when no valid visible time belongs to the selected card. Normalize only exact account labels such as “零钱”, “支付宝余额”, “花呗”, and masked bank-card labels; do not treat unrelated OCR text as an account. Apply these explicit event mappings:

```kotlin
val eventSignals = mapOf(
    "支付成功" to (TransactionEventKind.PAYMENT to Direction.EXPENSE),
    "已支付" to (TransactionEventKind.PAYMENT to Direction.EXPENSE),
    "收款到账" to (TransactionEventKind.RECEIPT to Direction.INCOME),
    "退款到账" to (TransactionEventKind.REFUND to Direction.INCOME),
    "退款成功" to (TransactionEventKind.REFUND to Direction.INCOME),
    "转入" to (TransactionEventKind.TRANSFER to Direction.INCOME),
    "转出" to (TransactionEventKind.TRANSFER to Direction.EXPENSE),
    "收到红包" to (TransactionEventKind.RED_PACKET to Direction.INCOME),
    "发出红包" to (TransactionEventKind.RED_PACKET to Direction.EXPENSE),
)
```

Generate `evidenceDigest` only from normalized platform, event, direction, amount, merchant, account, and visible time bucket. Never place `snapshot.elements` or concatenated raw text in a model, log, exception, or digest input after normalization.

- [ ] **Step 4: Run parser and existing notification parser tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.*" --tests "com.local.autobookkeeping.notification.PaymentParserTest"
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition app/src/test/java/com/local/autobookkeeping/recognition
git commit -m "feat: parse payment pages into recognition candidates"
```

---

### Task 5: Merchant Classification and Cross-Channel Coordinator

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/MerchantClassifier.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/RecognitionCoordinator.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/RecognitionDiagnostics.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/MerchantClassifierTest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/RecognitionCoordinatorTest.kt`

**Interfaces:**
- Consumes: `RecognitionStore`, parser candidates, categories, and transaction model.
- Produces: `MerchantClassifier.classify(candidate)`, `RecognitionCoordinator.process(candidate)`, and redacted status reporting.

- [ ] **Step 1: Write failing classification, matching, and dedupe tests**

```kotlin
@Test
fun `user rule overrides built in telecom classification`() = runTest {
    store.rule = MerchantRuleMatch("中国电信", "日用", null, priority = 100)
    assertEquals("日用", classifier.classify(telecomCandidate()).category)
}

@Test
fun `known telecom auto saves and duplicate digest is ignored`() = runTest {
    store.visibleCategories += "通讯"
    val first = coordinator.process(telecomCandidate(channel = CaptureChannel.NOTIFICATION))
    val duplicate = coordinator.process(telecomCandidate(channel = CaptureChannel.ACCESSIBILITY))
    assertEquals(RecognitionOutcome.Saved(1, pending = false), first)
    assertEquals(RecognitionOutcome.Duplicate, duplicate)
    assertEquals(1, store.transactions.size)
}

@Test
fun `notification then page enriches one pending transaction`() = runTest {
    coordinator.process(notificationCandidate(amount = 100, merchant = null))
    coordinator.process(pageCandidate(amount = 100, merchant = "中国电信"))
    assertEquals(1, store.transactions.size)
    assertEquals("中国电信", store.transactions.single().merchant)
    assertFalse(store.transactions.single().pendingConfirmation)
    assertEquals(2, store.receipts.size)
}

@Test
fun `two same amount matches never auto merge`() = runTest {
    store.seedPending(twoTransactionsSameAmount())
    val result = coordinator.process(pageCandidate(amount = 100, merchant = null))
    assertTrue(result is RecognitionOutcome.NeedsSelection)
}

@Test
fun `disabled automatic save always creates a reviewable pending row`() = runTest {
    autoSavePolicy.enabled = false
    val result = coordinator.process(telecomCandidate())
    assertEquals(RecognitionOutcome.Saved(1, pending = true), result)
}
```

- [ ] **Step 2: Run tests and verify missing coordinator fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.MerchantClassifierTest" --tests "com.local.autobookkeeping.recognition.RecognitionCoordinatorTest"
```

Expected: compilation failure for `RecognitionCoordinator`.

- [ ] **Step 3: Implement deterministic classification and matching**

```kotlin
data class Classification(val category: String?, val account: String?)

fun interface AutoSavePolicy {
    suspend fun isEnabled(): Boolean
}

enum class RecognitionDiagnostic {
    NONE,
    SAVED,
    NEEDS_CONFIRMATION,
    MULTIPLE_CANDIDATES,
    INCOMPLETE_FIELDS,
    PERMISSION_MISSING,
    CAPTURE_FAILED,
    OCR_FAILED,
}

interface RecognitionDiagnostics {
    val state: StateFlow<RecognitionDiagnostic>
    fun record(value: RecognitionDiagnostic)
}

class MerchantClassifier(private val store: RecognitionStore) {
    suspend fun classify(candidate: RecognitionCandidate): Classification {
        val merchant = candidate.merchant?.let(::normalizeMerchant)
        val user = merchant?.let { store.findMerchantRule(it) }
        if (user != null && store.isCategoryVisible(user.category)) {
            return Classification(user.category, user.account ?: candidate.account)
        }
        val category = when (candidate.eventKind) {
            TransactionEventKind.REFUND -> "退款"
            TransactionEventKind.TRANSFER -> "转账"
            TransactionEventKind.RED_PACKET -> "红包"
            else -> merchant?.takeIf(::isTelecomMerchant)?.let { "通讯" }
        }
        return Classification(
            category?.takeIf { store.isCategoryVisible(it) },
            candidate.account,
        )
    }
}
```

Implement `RecognitionCoordinator.process(candidate)` in this exact order:

1. Return `Duplicate` when `findReceipt(digest)` exists.
2. Search 15-minute pending matches by platform, direction, amount, and event.
3. If exactly one, enrich it and atomically add the receipt.
4. If more than one, return `NeedsSelection` with one `RecognitionChoice(candidate, matchTransactionId)` per existing row.
5. Search two-minute confirmed matches; when both merchants exist, require normalized equality.
6. If exactly one, attach the receipt and return `Duplicate`; if more than one, return `NeedsSelection` rather than guessing.
7. Find classification and optional refund origin.
8. Auto-save only when `AutoSavePolicy.isEnabled()` is true and direction, event, stable identity, visible category, and confidence ≥ 80 are all present.
9. Otherwise save `pendingConfirmation=true` with category “待确认”.

Add `resolve(choice: RecognitionChoice)`: a choice without `matchTransactionId` delegates to `process(candidate)`; a choice with an ID revalidates that the target still matches, atomically enriches that exact transaction, and attaches the receipt. A missing or changed target returns `Rejected(INCOMPLETE_FIELDS)` instead of creating a duplicate.

Implement `InMemoryRecognitionDiagnostics` with `MutableStateFlow<RecognitionDiagnostic>` only. It must never accept arbitrary strings, exceptions, merchant names, OCR output, or notification text.

Map channel to source:

```kotlin
private fun CaptureChannel.toSource() = when (this) {
    CaptureChannel.NOTIFICATION -> Source.NOTIFICATION
    CaptureChannel.ACCESSIBILITY -> Source.PAGE_RECOGNITION
    CaptureChannel.SCREENSHOT -> Source.SCREENSHOT
}
```

Never set `internalTransfer=true` automatically.

- [ ] **Step 4: Run coordinator and statistics tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.*" --tests "com.local.autobookkeeping.domain.StatisticsEngineTest"
```

Expected: all selected tests pass and pending rows remain excluded from statistics.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition app/src/test/java/com/local/autobookkeeping/recognition
git commit -m "feat: coordinate classification and recognition dedupe"
```

---

### Task 6: Real WeChat/Alipay Notifications Through the Coordinator

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/notification/WechatParser.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/notification/AlipayParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/NotificationCandidateMapper.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/notification/PaymentNotificationService.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/notification/PendingConfirmationNotifier.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/AutoBookkeepingApplication.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/notification/PaymentParserTest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/notification/NotificationCandidateMapperTest.kt`

**Interfaces:**
- Consumes: `PaymentCandidate` and Task 5 coordinator.
- Produces: strict real notification templates, normalized notification candidates, and coordinator-backed notification persistence.

- [ ] **Step 1: Add failing real-format and direction tests**

```kotlin
@Test
fun `real wechat paid notification creates an expense candidate`() {
    val candidate = WechatParser.parse(
        NotificationText("微信支付", "已支付¥1.00", "com.tencent.mm", instant),
    )!!
    assertEquals(100, candidate.amountMinor)
    assertEquals(Direction.EXPENSE, candidate.direction)
}

@Test
fun `wechat chat with paid words is rejected`() {
    assertNull(
        WechatParser.parse(
            NotificationText("张三", "我已支付¥1.00", "com.tencent.mm", instant),
        ),
    )
}

@Test
fun `alipay transfer and red packet preserve direction`() {
    assertDirection(Direction.INCOME, alipay("转账到账 人民币10.00"))
    assertDirection(Direction.EXPENSE, alipay("红包已支付 人民币2.00"))
}
```

- [ ] **Step 2: Run notification tests and verify real WeChat format fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.notification.*"
```

Expected: the `已支付¥1.00` test fails before the parser change.

- [ ] **Step 3: Implement strict templates and coordinator mapping**

For WeChat, accept `已支付` only when the trimmed title is exactly `微信支付`, the body contains exactly one CNY amount, and it contains no promotional or conflicting direction signal. Add `RED_PACKET` to the existing `PaymentEventKind` enum, and preserve transfer/red-packet directions rather than mapping them to generic ambiguous events.

Add:

```kotlin
fun PaymentCandidate.toRecognitionCandidate(): RecognitionCandidate =
    RecognitionCandidate(
        platform = when (sourcePackage) {
            NotificationRouter.WECHAT_PACKAGE -> PaymentPlatform.WECHAT
            NotificationRouter.ALIPAY_PACKAGE -> PaymentPlatform.ALIPAY
            else -> PaymentPlatform.OTHER
        },
        channel = CaptureChannel.NOTIFICATION,
        eventKind = eventKind.toTransactionEventKind(),
        amountMinor = amountMinor,
        direction = direction,
        merchant = merchant,
        account = null,
        occurredAt = occurredAt,
        confidence = confidence,
        evidenceDigest = redactedDigest,
    )
```

Replace `CandidateProcessor` use in `PaymentNotificationService` with `graph.recognitionCoordinator.process(candidate.toRecognitionCandidate())`. Notify only for `Saved(pending = true)` or `NeedsSelection`; successful auto-save uses a private local success notification.

- [ ] **Step 4: Run notification and service tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.notification.*"
```

Expected: all notification tests pass, including existing bank notification regression tests.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/notification app/src/main/java/com/local/autobookkeeping/AutoBookkeepingApplication.kt app/src/test/java/com/local/autobookkeeping/notification
git commit -m "fix: recognize real WeChat and Alipay notifications"
```

---

### Task 7: Bundled Local Chinese OCR

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/capture/LocalTextRecognizer.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/capture/MlKitTextRecognizer.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/capture/OcrLineMapper.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/capture/OcrLineMapperTest.kt`

**Interfaces:**
- Consumes: Android `Bitmap`.
- Produces: `LocalTextRecognizer.recognize(bitmap)` and geometry-preserving `ObservedTextElement` output.

- [ ] **Step 1: Write the failing OCR line mapping test**

```kotlin
@Test
fun `mapper preserves lines and bounds without retaining full image text`() {
    val result = OcrLineMapper.map(
        listOf(
            RawOcrLine("中国电信", 20, 40, 180, 80),
            RawOcrLine("¥1.00", 300, 200, 480, 280),
        ),
    )
    assertEquals("中国电信", result[0].text)
    assertEquals(TextBounds(300, 200, 480, 280), result[1].bounds)
}
```

- [ ] **Step 2: Run the OCR mapper test and verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.capture.OcrLineMapperTest"
```

Expected: missing `OcrLineMapper`.

- [ ] **Step 3: Add bundled ML Kit and the suspend adapter**

Add version and dependency:

```toml
mlKitTextRecognition = "16.0.1"
mlkit-text-recognition-chinese = { module = "com.google.mlkit:text-recognition-chinese", version.ref = "mlKitTextRecognition" }
```

```kotlin
implementation(libs.mlkit.text.recognition.chinese)
```

Implement:

```kotlin
fun interface LocalTextRecognizer {
    suspend fun recognize(bitmap: Bitmap): List<ObservedTextElement>
}

class MlKitTextRecognizer : LocalTextRecognizer, Closeable {
    private val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): List<ObservedTextElement> =
        suspendCancellableCoroutine { continuation ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    continuation.resume(
                        OcrLineMapper.map(
                            text.textBlocks.flatMap { block ->
                                block.lines.mapNotNull { line ->
                                    line.boundingBox?.let { box ->
                                        RawOcrLine(line.text, box.left, box.top, box.right, box.bottom)
                                    }
                                }
                            },
                        ),
                    )
                }
                .addOnFailureListener(continuation::resumeWithException)
        }

    override fun close() = client.close()
}
```

Do not expose `Text.text`, `TextBlock`, or `Bitmap` outside this adapter.

- [ ] **Step 4: Run OCR tests and compile the debug APK**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.capture.*"
.\gradlew.bat assembleDebug
```

Expected: tests pass and Gradle resolves the bundled model.

- [ ] **Step 5: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/local/autobookkeeping/recognition/capture app/src/test/java/com/local/autobookkeeping/recognition/capture
git commit -m "feat: add bundled local Chinese OCR"
```

---

### Task 8: WeChat/Alipay Accessibility Page Recognition

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AccessibilitySnapshotFactory.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/PaymentPageAccessibilityService.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/capture/AccessibilityCaptureGateway.kt`
- Create: `app/src/main/res/xml/payment_page_accessibility_service.xml`
- Create: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/RecognitionDebouncerTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/recognition/accessibility/AccessibilityServiceContractTest.kt`

**Interfaces:**
- Consumes: page parsers, OCR adapter, coordinator, DataStore platform switches.
- Produces: a read-only accessibility service and an attach/detach one-shot screenshot provider.

- [ ] **Step 1: Write failing package-scope, debounce, and OCR-fallback tests**

```kotlin
@Test
fun `controller ignores packages outside WeChat and Alipay`() = runTest {
    controller.onWindowChanged(page("com.example.bank", "支付成功", "¥1.00"))
    assertEquals(0, coordinator.calls)
    assertEquals(0, screenshot.calls)
}

@Test
fun `incomplete signed payment page uses one screenshot fallback`() = runTest {
    controller.onWindowChanged(page("com.tencent.mm", "微信支付", "账单详情"))
    assertEquals(1, screenshot.calls)
    assertEquals(1, coordinator.calls)
}

@Test
fun `same normalized page inside debounce window is processed once`() = runTest {
    repeat(3) { controller.onWindowChanged(wechatPaymentPage()) }
    assertEquals(1, coordinator.calls)
}
```

- [ ] **Step 2: Run accessibility unit tests and verify missing controller fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.accessibility.*"
```

Expected: missing controller/service contracts.

- [ ] **Step 3: Implement read-only service and capture broker**

The service XML must contain:

```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:packageNames="com.tencent.mm,com.eg.android.AlipayGphone"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="250"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/payment_page_accessibility_description" />
```

Declare only `BIND_ACCESSIBILITY_SERVICE`; do not declare gesture capability. Traverse visible nodes into `PageSnapshot`, recycle/release node references, debounce by normalized digest, and only invoke OCR when the parser reports a signed but incomplete payment page. Every automatic fallback bitmap must be passed through `try/finally` and recycled after OCR, including rejected and exceptional paths.

Implement gateway:

```kotlin
enum class CaptureFailure {
    SECURE_WINDOW,
    TOO_FREQUENT,
    NO_ACCESSIBILITY_ACCESS,
    INVALID_DISPLAY,
    INTERNAL,
}

sealed interface CaptureResult {
    data class Success(val bitmap: Bitmap, val foregroundPackage: String?) : CaptureResult
    data class Failure(val reason: CaptureFailure) : CaptureResult
    data object RequiresSystemConsent : CaptureResult
}

class AccessibilityCaptureGateway {
    fun attach(provider: suspend () -> CaptureResult)
    fun detach()
    suspend fun capture(): CaptureResult
}
```

On API 30+, call `takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)`, copy the `HardwareBuffer` into a software bitmap, and always close the hardware buffer. Map secure-window, interval-too-short, and missing-access errors to redacted failure enums.

- [ ] **Step 4: Run unit, contract, and manifest tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.accessibility.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.recognition.accessibility.AccessibilityServiceContractTest
```

Expected: package scope, no gesture actions, XML capabilities, and service declaration pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition/accessibility app/src/main/java/com/local/autobookkeeping/recognition/capture/AccessibilityCaptureGateway.kt app/src/main/res app/src/main/AndroidManifest.xml app/src/test/java/com/local/autobookkeeping/recognition/accessibility app/src/androidTest/java/com/local/autobookkeeping/recognition/accessibility
git commit -m "feat: recognize WeChat and Alipay payment pages"
```

---

### Task 9: Quick Settings Screenshot Bookkeeping

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/capture/ScreenshotRecognitionController.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotBookkeepingTileService.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotCaptureActivity.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/tile/QuickSettingsTileLauncher.kt`
- Create: `app/src/main/res/drawable/ic_screenshot_bookkeeping.xml`
- Create: `app/src/main/res/values/styles.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/capture/ScreenshotRecognitionControllerTest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/tile/QuickSettingsTileLauncherTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/recognition/tile/ScreenshotTileContractTest.kt`

**Interfaces:**
- Consumes: accessibility capture, OCR, generic parser, coordinator.
- Produces: a Quick Settings tile, transparent capture activity, and result returned as `RecognitionOutcome`.

- [ ] **Step 1: Write failing one-shot lifecycle tests**

```kotlin
@Test
fun `screenshot bitmap is recycled after success`() = runTest {
    val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
    val result = controller.process(bitmap, "com.tencent.mm", instant)
    assertTrue(result is RecognitionOutcome.Saved)
    assertTrue(bitmap.isRecycled)
}

@Test
fun `multiple cards return review options without persistence`() = runTest {
    ocr.lines = twoPaymentCards()
    val result = controller.process(bitmap(), "com.example.bank", instant)
    assertTrue(result is RecognitionOutcome.NeedsSelection)
    assertEquals(0, coordinator.savedCount)
}
```

- [ ] **Step 2: Run screenshot tests and verify missing controller fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.capture.ScreenshotRecognitionControllerTest" --tests "com.local.autobookkeeping.recognition.tile.QuickSettingsTileLauncherTest"
```

Expected: missing controller/tile launcher.

- [ ] **Step 3: Implement tile, transparent activity, and screenshot pipeline**

Tile click:

```kotlin
override fun onClick() {
    val intent = Intent(this, ScreenshotCaptureActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
```

Use a transparent, no-animation activity. After `onResume`, wait an injected 350 ms panel-collapse delay, then call `AccessibilityCaptureGateway.capture()`. Preserve the last external foreground package recorded by the accessibility service before the transparent activity opens.

`ScreenshotRecognitionController.process()` must use `try/finally { bitmap.recycle() }`, map OCR lines to a page snapshot, select the platform parser from the recorded package, use `GenericBillParser` otherwise, and forward only normalized candidates to the coordinator.

Map parser outcomes exactly:

```kotlin
return when (val extracted = parser.parse(snapshot)) {
    is ExtractionOutcome.Single -> coordinator.process(extracted.candidate)
    is ExtractionOutcome.Multiple -> RecognitionOutcome.NeedsSelection(
        extracted.options.map { RecognitionChoice(candidate = it) },
    )
    is ExtractionOutcome.Rejected -> RecognitionOutcome.Rejected(extracted.reason)
}
```

Declare tile service with `android.permission.BIND_QUICK_SETTINGS_TILE`, exported `true`, label `截屏记账`, and the custom vector icon. `QuickSettingsTileLauncher.requestAdd()` returns `TileAddResult.Requested`, `TileAddResult.AlreadyAdded`, `TileAddResult.ManualInstructions`, or `TileAddResult.Failed`; on API 33+ it calls `StatusBarManager.requestAddTileService`, while older versions return `ManualInstructions` instead of failing.

- [ ] **Step 4: Run tile tests and build**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.capture.ScreenshotRecognitionControllerTest" --tests "com.local.autobookkeeping.recognition.tile.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.recognition.tile.ScreenshotTileContractTest
.\gradlew.bat assembleDebug
```

Expected: all commands pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition/capture/ScreenshotRecognitionController.kt app/src/main/java/com/local/autobookkeeping/recognition/tile app/src/main/res app/src/main/AndroidManifest.xml app/src/test/java/com/local/autobookkeeping/recognition/capture/ScreenshotRecognitionControllerTest.kt app/src/test/java/com/local/autobookkeeping/recognition/tile app/src/androidTest/java/com/local/autobookkeeping/recognition/tile
git commit -m "feat: add Quick Settings screenshot bookkeeping"
```

---

### Task 10: One-Frame MediaProjection Fallback

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/tile/ProjectionCaptureService.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/tile/OneShotProjectionController.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/tile/ScreenshotCaptureActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/tile/OneShotProjectionControllerTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/recognition/tile/ProjectionServiceContractTest.kt`

**Interfaces:**
- Consumes: `ScreenshotRecognitionController`.
- Produces: consent-per-use API 26–29 and accessibility-failure fallback; one projection token creates exactly one virtual display.

- [ ] **Step 1: Write failing one-frame resource cleanup tests**

```kotlin
@Test
fun `one shot projection closes every resource after first image`() = runTest {
    controller.start(grantedToken)
    fakeImageReader.emit(image)
    assertEquals(1, recognizer.calls)
    assertTrue(image.closed)
    assertTrue(fakeVirtualDisplay.released)
    assertTrue(fakeProjection.stopped)
    assertTrue(fakeImageReader.closed)
}

@Test
fun `second image from same token is ignored`() = runTest {
    controller.start(grantedToken)
    fakeImageReader.emit(imageOne)
    fakeImageReader.emit(imageTwo)
    assertEquals(1, recognizer.calls)
}
```

- [ ] **Step 2: Run projection tests and verify missing controller fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.tile.OneShotProjectionControllerTest"
```

Expected: missing `OneShotProjectionController`.

- [ ] **Step 3: Implement consent, foreground service, and one-frame capture**

Add:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

Declare `ProjectionCaptureService` with `android:foregroundServiceType="mediaProjection"` and `exported="false"`.

When accessibility capture returns `RequiresSystemConsent` or an unsupported error, `ScreenshotCaptureActivity` launches `MediaProjectionManager.createScreenCaptureIntent()`. On success it starts the foreground service with the one-use result intent. The service must:

1. Call `startForeground()` immediately with a private “正在识别当前页面” notification.
2. Call `getMediaProjection()` once.
3. Create one `ImageReader` and one virtual display.
4. Acquire one image, convert row-stride/pixel-stride safely to a cropped software bitmap, and process it.
5. Close the image, reader, virtual display, projection, and bitmap in `finally`.
6. Stop itself and remove the foreground notification.

Cancellation or denial returns a redacted diagnostic and creates no record.

- [ ] **Step 4: Run projection tests and manifest contract**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.tile.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.recognition.tile.ProjectionServiceContractTest
```

Expected: single-use, cleanup, permissions, and service type tests pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition/tile app/src/main/AndroidManifest.xml app/src/test/java/com/local/autobookkeeping/recognition/tile app/src/androidTest/java/com/local/autobookkeeping/recognition/tile
git commit -m "feat: add one-shot screen capture fallback"
```

---

### Task 11: Automatic Bookkeeping Settings and Permission Recovery

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/data/SettingsRepository.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/AutomaticBookkeepingAccess.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/notification/NotificationRouter.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/settings/AutomaticBookkeepingSettingsScreen.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/data/SettingsRepositoryTest.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/ui/settings/SettingsViewModelTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/AutomaticBookkeepingSettingsScreenTest.kt`

**Interfaces:**
- Consumes: notification/accessibility status, tile launcher, diagnostics.
- Produces: total switch, WeChat/Alipay switches, auto-save switch, two-card subpage, and system setting launch actions.

- [ ] **Step 1: Add failing settings default and UI state tests**

```kotlin
@Test
fun `automatic settings default to off except high confidence auto save`() = runTest {
    assertFalse(repository.autoBookkeepingEnabled.first())
    assertFalse(repository.wechatEnabled.first())
    assertFalse(repository.alipayEnabled.first())
    assertTrue(repository.autoSaveHighConfidence.first())
}

@Test
fun `existing enabled source migrates total switch to on`() = runTest {
    seedPreferences(wechatEnabled = true, autoBookkeepingEnabled = null)
    assertTrue(repository.autoBookkeepingEnabled.first())
}

@Test
fun `resume refresh shows missing accessibility without disabling manual bookkeeping`() {
    val viewModel = SettingsViewModel(
        repository = fakeSettings,
        notificationAccess = { true },
        accessibilityAccess = { false },
    )
    viewModel.refreshAutomaticAccess()
    assertEquals("页面识别服务未开启", viewModel.state.value.automaticAccessMessage)
}
```

- [ ] **Step 2: Run settings tests and verify missing settings fail**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.data.SettingsRepositoryTest" --tests "com.local.autobookkeeping.ui.settings.SettingsViewModelTest"
```

Expected: missing flows and access message.

- [ ] **Step 3: Add DataStore settings, access checks, and the two-card page**

Extend `SettingsOperations`:

```kotlin
val autoBookkeepingEnabled: Flow<Boolean>
val autoSaveHighConfidence: Flow<Boolean>
suspend fun setAutoBookkeepingEnabled(enabled: Boolean)
suspend fun setAutoSaveHighConfidence(enabled: Boolean)
```

Use preference keys `auto_bookkeeping_enabled` and `auto_save_high_confidence`. On a fresh store the total switch defaults to `false` and high-confidence auto-save defaults to `true`. When the total-switch key is absent but an existing WeChat, Alipay, or bank source is already enabled, expose the total switch as `true` so this upgrade does not silently disable the user’s current notification bookkeeping. Existing `wechatEnabled` and `alipayEnabled` become platform source switches shared by notification and page recognition.

Gate notification routing and automatic page recognition on `autoBookkeepingEnabled`; user-triggered screenshot bookkeeping remains available even when the automatic total switch is off.

Expose `autoSaveHighConfidence.first()` through the `AutoSavePolicy` consumed by `RecognitionCoordinator`, so turning the switch off forces otherwise high-confidence automatic results into pending confirmation.

Add `AccessibilityAccess.isEnabled(context)` by reading `AccessibilityManager.getEnabledAccessibilityServiceList()` and exact component identity. Add launch helpers for notification access, accessibility settings, tile add request, and the device’s battery optimization settings; failure messages remain stable Chinese and never expose internal paths.

`SettingsScreen` gains an “自动记账” row that opens `AutomaticBookkeepingSettingsScreen` using `rememberSaveable`; a nested `BackHandler` returns to settings. The subpage has:

```kotlin
AppSectionCard {
    SectionTitle("消费完毕自动记")
    ToggleRow("自动记账", state.autoBookkeepingEnabled, enabled, onAutoChanged)
    ToggleRow("微信", state.wechatEnabled, enabled, onWechatChanged)
    ToggleRow("支付宝", state.alipayEnabled, enabled, onAlipayChanged)
    PermissionRow("通知读取", state.notificationAccessEnabled, onOpenNotificationAccess)
    PermissionRow("页面识别", state.accessibilityAccessEnabled, onOpenAccessibility)
    Text("支持消费、收款、退款、转账和红包", style = MaterialTheme.typography.bodySmall)
}
AppSectionCard {
    SectionTitle("一键截屏记账")
    OutlinedButton(onClick = onAddQuickSettingsTile) { Text("添加截屏记账按钮") }
    ToggleRow("高可信结果自动入账", state.autoSaveHighConfidence, enabled, onAutoSaveChanged)
    Text("不确定时始终让我确认", style = MaterialTheme.typography.bodySmall)
}
```

Collect state on resume so revoked permissions are shown immediately. Keep typography at `titleMedium/bodyMedium/bodySmall`.

- [ ] **Step 4: Run settings unit and Compose tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.data.SettingsRepositoryTest" --tests "com.local.autobookkeeping.ui.settings.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.AutomaticBookkeepingSettingsScreenTest
```

Expected: both commands pass under all three themes.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/data/SettingsRepository.kt app/src/main/java/com/local/autobookkeeping/AutomaticBookkeepingAccess.kt app/src/main/java/com/local/autobookkeeping/notification/NotificationRouter.kt app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt app/src/main/java/com/local/autobookkeeping/ui/settings app/src/main/java/com/local/autobookkeeping/MainActivity.kt app/src/test/java/com/local/autobookkeeping/data/SettingsRepositoryTest.kt app/src/test/java/com/local/autobookkeeping/ui/settings app/src/androidTest/java/com/local/autobookkeeping/AutomaticBookkeepingSettingsScreenTest.kt
git commit -m "feat: add automatic bookkeeping settings"
```

---

### Task 12: Review, Confirmation, and Merchant Rule Learning

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/ReviewRequestCodec.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/recognition/RecognitionReviewScreen.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/notification/PendingConfirmationNotifier.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/ReviewRequestCodecTest.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/ui/entry/EntryViewModelTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/RecognitionReviewFlowTest.kt`

**Interfaces:**
- Consumes: `RecognitionOutcome.NeedsSelection`, pending transaction ID, coordinator, and atomic recognition confirmation operations.
- Produces: safe intent codec, candidate selector, direct pending-entry opening, and rule learning after confirmation.

- [ ] **Step 1: Add failing codec, confirmation, and learning tests**

```kotlin
@Test
fun `review codec round trips normalized fields only`() {
    val request = ReviewRequest(listOf(RecognitionChoice(candidate())))
    val encoded = codec.encode(request)
    assertEquals(request, codec.decode(encoded))
    assertFalse(encoded.contains("pageRawText"))
    assertFalse(encoded.contains("notificationBody"))
}

@Test
fun `confirming recognized merchant remembers selected category`() = runTest {
    viewModel.edit(pendingTelecomTransaction())
    viewModel.update { it.copy(category = "通讯") }
    assertTrue(viewModel.confirmPending().isSuccess)
    assertEquals("通讯", confirmation.saved.single().category)
    assertEquals("中国电信", confirmation.saved.single().merchant)
}
```

- [ ] **Step 2: Run review and entry tests and verify failures**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.ReviewRequestCodecTest" --tests "com.local.autobookkeeping.ui.entry.EntryViewModelTest"
```

Expected: missing codec and merchant-rule callback.

- [ ] **Step 3: Implement safe launch payloads and review UI**

Define:

```kotlin
data class ReviewRequest(val options: List<RecognitionChoice>) {
    init {
        require(options.size in 1..5)
    }
}
```

Serialize only normalized `RecognitionChoice` fields with existing `kotlinx.serialization`; cap options at five, encoded payload at 32 KiB, confidence at 0–100, digest at lowercase SHA-256, and `matchTransactionId` at a positive value when present. Reject unknown enum names and malformed amounts.

`PendingConfirmationNotifier.notifyPending(transactionId)` launches `MainActivity` with:

```kotlin
const val EXTRA_PENDING_TRANSACTION_ID = "pending_transaction_id"
```

For multiple options, send:

```kotlin
const val EXTRA_REVIEW_REQUEST = "recognition_review_request"
```

Use the positive transaction ID as the pending-notification ID/request code. For selection requests, derive a stable positive ID from the first eight hex characters of the first candidate digest. This prevents one unresolved transaction from overwriting another request’s `PendingIntent`.

`MainActivity.onCreate()` and `onNewIntent()` pass either launch request into Compose. A pending ID loads `LedgerRepository.findById()`, calls `entryViewModel.edit()`, and opens Entry. A review request shows `RecognitionReviewScreen`, whose rows display direction, amount, merchant/event, and time. Selecting one calls `RecognitionCoordinator.resolve(selected)`; a pending result opens Entry, a saved result opens Ledger, and duplicate/rejected results show a short message.

Add optional `RecognitionConfirmationOperations` to `EntryViewModel`. For a recognized pending transaction, bypass the ordinary repository save and call the atomic operation:

```kotlin
recognitionConfirmation.confirmAndRemember(transaction)
```

Manual and non-pending transactions continue to use `LedgerRepository.save()`. The atomic implementation from Task 2 does not learn a rule for a blank merchant.

- [ ] **Step 4: Run unit and full review flow tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.ReviewRequestCodecTest" --tests "com.local.autobookkeeping.ui.entry.*"
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.RecognitionReviewFlowTest
```

Expected: normalized candidate selection, direct pending opening, edits, confirmation, and learned category pass.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition/ReviewRequestCodec.kt app/src/main/java/com/local/autobookkeeping/ui/recognition app/src/main/java/com/local/autobookkeeping/notification/PendingConfirmationNotifier.kt app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt app/src/main/java/com/local/autobookkeeping/MainActivity.kt app/src/test/java/com/local/autobookkeeping/recognition/ReviewRequestCodecTest.kt app/src/test/java/com/local/autobookkeeping/ui/entry app/src/androidTest/java/com/local/autobookkeeping/RecognitionReviewFlowTest.kt
git commit -m "feat: review and learn recognized transactions"
```

---

### Task 13: Application Wiring, Regression Suite, and Privacy Contracts

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/AutoBookkeepingApplication.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/androidTest/java/com/local/autobookkeeping/EndToEndTest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/PrivacyContractTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/AutomaticBookkeepingEndToEndTest.kt`
- Modify: `docs/testing/manual-android-checklist.md`

**Interfaces:**
- Consumes: all preceding components.
- Produces: one application graph, full automated regression evidence, and an updated manual checklist.

- [ ] **Step 1: Add failing graph and privacy contract tests**

```kotlin
@Test
fun `recognition implementation does not persist screenshots or raw text`() {
    val root = projectRoot()
    val forbidden = setOf("FileOutputStream", "openFileOutput", "MediaStore.Images")
    val offenders = Files.walk(
        root.resolve("app/src/main/java/com/local/autobookkeeping/recognition"),
    ).use { paths ->
        paths.filter(Files::isRegularFile)
            .filter { path -> forbidden.any(Files.readString(path)::contains) }
            .map(Path::toString)
            .toList()
    }
    assertEquals(emptyList<String>(), offenders)
}

@Test
fun `manifest has no internet storage overlay or gesture capability`() {
    val root = projectRoot()
    val manifest = Files.readString(root.resolve("app/src/main/AndroidManifest.xml"))
    val serviceXml = Files.readString(
        root.resolve("app/src/main/res/xml/payment_page_accessibility_service.xml"),
    )
    assertFalse(manifest.contains("android.permission.INTERNET"))
    assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
    assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
    assertFalse(serviceXml.contains("canPerformGestures=\"true\""))
}

private fun projectRoot(): Path {
    val current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    return listOfNotNull(current, current.parent).first {
        Files.exists(it.resolve("app/src/main/AndroidManifest.xml"))
    }
}
```

- [ ] **Step 2: Run the whole suite before final wiring**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Expected: graph/privacy tests expose any remaining wiring or forbidden API issues.

- [ ] **Step 3: Complete the application graph and end-to-end assertions**

Wire singleton instances in `ApplicationGraph`:

```kotlin
val recognitionStore by lazy { RoomRecognitionStore(database) }
val merchantClassifier by lazy { MerchantClassifier(recognitionStore) }
val recognitionDiagnostics = InMemoryRecognitionDiagnostics()
val recognitionCoordinator by lazy {
    RecognitionCoordinator(
        store = recognitionStore,
        classifier = merchantClassifier,
        autoSavePolicy = AutoSavePolicy { settingsRepository.autoSaveHighConfidence.first() },
        diagnostics = recognitionDiagnostics,
    )
}
val localTextRecognizer by lazy { MlKitTextRecognizer() }
val accessibilityCaptureGateway = AccessibilityCaptureGateway()
val screenshotRecognitionController by lazy {
    ScreenshotRecognitionController(
        localTextRecognizer,
        WechatPageParser,
        AlipayPageParser,
        GenericBillParser,
        recognitionCoordinator,
    )
}
```

Ensure notification, accessibility, tile, projection, settings, review, and entry confirmation resolve dependencies only from this graph. Add an instrumentation flow using sanitized candidates that verifies one confirmed expense and one confirmed income appear once in Ledger, day/week/month/year statistics, and the correct pie chart direction.

Update `docs/testing/manual-android-checklist.md` with unchecked rows for WeChat and Alipay consumption, receipt, refund, transfer, red packet, one-tap screenshot, same-amount dedupe, repeated-page dedupe, and existing-data preservation.

- [ ] **Step 4: Run all automated verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

Expected: every command exits successfully. Record the exact task count and APK path in the checklist notes.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main app/src/test app/src/androidTest docs/testing/manual-android-checklist.md
git commit -m "test: verify automatic and screenshot bookkeeping"
```

---

### Task 14: Replacement Install and Real-Device Acceptance

**Files:**
- Modify: `docs/testing/manual-android-checklist.md`

**Interfaces:**
- Consumes: `app/build/outputs/apk/debug/app-debug.apk`, connected device `81c74ce1`.
- Produces: preserved app data and recorded real-device results for both modes.

- [ ] **Step 1: Verify the exact device without restarting ADB**

Run each command with a 10-second host timeout:

```powershell
adb -s 81c74ce1 get-state
adb -s 81c74ce1 shell getprop ro.product.model
adb -s 81c74ce1 shell getprop ro.build.version.sdk
```

Expected: `device`, `PKG110`, and `36`.

- [ ] **Step 2: Record the pre-install data baseline**

With a 10-second host timeout:

```powershell
adb -s 81c74ce1 shell am force-stop com.local.autobookkeeping
adb -s 81c74ce1 shell monkey -p com.local.autobookkeeping -c android.intent.category.LAUNCHER 1
```

Read the visible ledger/home totals manually and record the existing transaction count and today total in the checklist. Do not use `pm clear`, uninstall, or database deletion.

- [ ] **Step 3: Replacement-install the verified APK**

With a 10-second host timeout:

```powershell
adb -s 81c74ce1 install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

- [ ] **Step 4: Complete the permission guide and both feature flows**

Manually:

1. Open “我的 → 自动记账”.
2. Enable the total switch, notification access, WeChat, Alipay, and page recognition.
3. Add the “截屏记账” Quick Settings tile.
4. Confirm the pre-install transaction count and today total are unchanged.
5. Make or replay a small WeChat payment and open its payment result page.
6. Make or replay a small Alipay payment and open its result page.
7. Validate controlled samples for income, refund, transfer, and red packet in both platforms.
8. On another visible bill page, tap “截屏记账”.
9. Repeat the same page and create two same-amount examples.
10. Confirm only confirmed rows affect day/week/month/year statistics and the correct income/expense pie.

Expected: clear results or review prompts; no duplicate records; no lost existing data.

- [ ] **Step 5: Record evidence and commit the checklist**

Mark each row pass/fail with observed amount, direction, category, and whether review was required. Do not paste notification bodies, screenshots, page text, balances, or order numbers.

```powershell
git add docs/testing/manual-android-checklist.md
git commit -m "test: accept automatic bookkeeping on device"
```

If a row fails, stop at that row, preserve device/app state, and start the systematic-debugging workflow from the redacted symptom before claiming completion.

---

## Final Verification Gate

Before reporting completion:

1. Run `git status --short` and confirm only intentional checklist evidence remains.
2. Run `.\gradlew.bat testDebugUnitTest`, `.\gradlew.bat lintDebug`, `.\gradlew.bat assembleDebug`, and `.\gradlew.bat connectedDebugAndroidTest`.
3. Confirm the installed package was updated with `install -r`, not cleared or uninstalled.
4. Confirm the pre-install records still exist.
5. Confirm no screenshot, raw OCR text, notification body, or page text appears in files, database, logs, backups, or checklist.
6. Confirm every applicable manual checklist row has an explicit pass/fail result.
7. Use the verification-before-completion skill before any success claim.
