# Android Auto-Bookkeeping MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a private, offline-first Android app that captures supported payment notifications, lets the user maintain every transaction manually, and reports income and expense by date and category in three switchable visual themes.

**Architecture:** A single Android application module uses focused packages for domain rules, Room persistence, notification parsing, backup, and Compose features. Parsers produce candidates, a ledger service applies deduplication and classification rules, repositories persist validated records, and ViewModels expose immutable screen state.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Room 2.8.4, DataStore 1.2.1, AndroidX Lifecycle, coroutines/Flow, WorkManager, JUnit 5, Robolectric, Compose UI tests, AGP 9.3.0, Gradle 9.5.0, JDK 17, compile/target SDK 37, min SDK 26.

## Global Constraints

- Package/application ID: `com.local.autobookkeeping`; display name: `自记账`.
- Use AGP 9.3.0, Gradle 9.5.0, JDK 17, compile SDK 37, target SDK 37, and min SDK 26.
- Use stable libraries only: Compose BOM 2026.06.00, Room 2.8.4, and DataStore 1.2.1.
- Store money as `Long` minor units; never use floating-point arithmetic for ledger values.
- Keep transactions, notification parsing, merchant rules, settings, and backups on-device; perform no network requests.
- Internal transfers remain visible but are excluded from income, expense, balance, pie charts, and rankings.
- Categories are independent of direction; transfer, red-envelope, and gift-money categories can be income or expense.
- Page title 20sp, body 14sp, supporting copy 11–12sp, and primary amounts 26–28sp; respect system font scaling.
- Keep Clear Mint, Receipt Ledger, and Paper Minimal themes switchable without recreating or losing screen state.

---

## Planned File Map

```text
app/src/main/java/com/local/autobookkeeping/
  AutoBookkeepingApp.kt                 application graph
  MainActivity.kt                       Compose host and navigation
  domain/model/Transaction.kt           ledger types and invariants
  domain/model/Category.kt              category types and seed data
  domain/statistics/StatisticsEngine.kt date filtering and aggregation
  data/db/AppDatabase.kt                Room database
  data/db/TransactionDao.kt              transaction queries
  data/db/Entities.kt                    Room entities and mappings
  data/LedgerRepository.kt               ledger write/read boundary
  data/SettingsRepository.kt             DataStore preferences
  notification/PaymentParser.kt          parser contract and candidates
  notification/WechatParser.kt           WeChat notification rules
  notification/AlipayParser.kt           Alipay notification rules
  notification/PaymentNotificationService.kt Android listener service
  backup/BackupManager.kt                versioned JSON backup/restore
  ui/navigation/AppDestination.kt        five bottom destinations
  ui/home/HomeScreen.kt                  daily summary and recent entries
  ui/ledger/LedgerScreen.kt              search/filter/edit entry point
  ui/entry/EntryScreen.kt                manual and auto-candidate editor
  ui/statistics/StatisticsScreen.kt      range controls, pie and ranking
  ui/settings/SettingsScreen.kt          permissions, themes, data controls
  ui/theme/AppTheme.kt                   three selectable themes
```

---

### Task 1: Buildable Android Shell and Five-Destination Navigation

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/navigation/AppDestination.kt`
- Test: `app/src/androidTest/java/com/local/autobookkeeping/NavigationTest.kt`

**Interfaces:**
- Produces: `enum class AppDestination(val label: String)` with `HOME`, `LEDGER`, `ENTRY`, `STATISTICS`, and `SETTINGS`.
- Produces: `@Composable fun AutoBookkeepingRoot()` as the application UI entry point.

- [ ] **Step 1: Write the failing navigation test**

```kotlin
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensEveryBottomDestination() {
        listOf("首页", "账单", "统计", "设置").forEach { label ->
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithTag("screen-$label").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("记一笔").performClick()
        compose.onNodeWithTag("screen-记一笔").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the test and verify the project is not yet buildable**

Run: `./gradlew.bat :app:connectedDebugAndroidTest --tests "*NavigationTest"`  
Expected: FAIL because the Gradle project and `MainActivity` do not exist.

- [ ] **Step 3: Create the Gradle shell and navigation implementation**

Use `pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }`, AGP `9.3.0`, Gradle wrapper `9.5.0`, `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`, Compose BOM `2026.06.00`, Material 3, navigation-compose, lifecycle-runtime-compose, activity-compose, Room, DataStore, WorkManager, JUnit, AndroidX test, Robolectric, and Compose test dependencies.

```kotlin
enum class AppDestination(val label: String) {
    HOME("首页"), LEDGER("账单"), ENTRY("记一笔"), STATISTICS("统计"), SETTINGS("设置")
}

@Composable
fun AutoBookkeepingRoot() {
    var current by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    Scaffold(bottomBar = {
        NavigationBar {
            AppDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = current == destination,
                    onClick = { current = destination },
                    modifier = if (destination == AppDestination.ENTRY) {
                        Modifier.semantics { contentDescription = "记一笔" }
                    } else Modifier,
                    icon = { Text(if (destination == AppDestination.ENTRY) "＋" else destination.label.take(1)) },
                    label = { if (destination != AppDestination.ENTRY) Text(destination.label) }
                )
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().testTag("screen-${current.label}")) {
            Text(current.label, modifier = Modifier.padding(20.dp), fontSize = 20.sp)
        }
    }
}
```

Keep the add button visually level with the navigation bar in the final root layout; do not use the protruding yellow treatment rejected during design.

- [ ] **Step 4: Build and run the navigation test**

Run: `./gradlew.bat :app:assembleDebug :app:connectedDebugAndroidTest --tests "*NavigationTest"`  
Expected: `BUILD SUCCESSFUL`, with `opensEveryBottomDestination` passing.

- [ ] **Step 5: Commit the shell**

```powershell
git add settings.gradle.kts build.gradle.kts gradle app
git commit -m "feat: scaffold Android app navigation"
```

### Task 2: Domain Model and Statistics Rules

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/domain/model/Transaction.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/domain/model/Category.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/domain/statistics/StatisticsEngine.kt`
- Test: `app/src/test/java/com/local/autobookkeeping/domain/StatisticsEngineTest.kt`

**Interfaces:**
- Produces: `Transaction`, `Direction`, `Source`, `DateRange`, `CategoryTotal`, and `StatisticsSummary`.
- Produces: `StatisticsEngine.summarize(transactions, range, direction): StatisticsSummary`.

- [ ] **Step 1: Write failing tests for direction, internal transfers, and date boundaries**

```kotlin
class StatisticsEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun tx(id: Long, cents: Long, direction: Direction, day: Int, internal: Boolean = false) =
        Transaction(id, cents, "CNY", direction, "转账", Instant.parse("2026-07-%02dT04:00:00Z".format(day)), internalTransfer = internal)

    @Test fun `same category can count as income and expense while internal transfer is excluded`() {
        val rows = listOf(
            tx(1, 10000, Direction.INCOME, 22),
            tx(2, 4000, Direction.EXPENSE, 22),
            tx(3, 50000, Direction.INCOME, 22, internal = true)
        )
        val range = DateRange(LocalDate.of(2026, 7, 22), LocalDate.of(2026, 7, 22), zone)
        assertEquals(10000, StatisticsEngine.summarize(rows, range, Direction.INCOME).totalMinor)
        assertEquals(4000, StatisticsEngine.summarize(rows, range, Direction.EXPENSE).totalMinor)
    }
}
```

- [ ] **Step 2: Run the domain test and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StatisticsEngineTest"`  
Expected: FAIL with unresolved domain types.

- [ ] **Step 3: Implement immutable domain types and aggregation**

```kotlin
enum class Direction { INCOME, EXPENSE }
enum class Source { MANUAL, NOTIFICATION }

data class Transaction(
    val id: Long = 0,
    val amountMinor: Long,
    val currency: String = "CNY",
    val direction: Direction,
    val category: String,
    val occurredAt: Instant,
    val merchant: String = "",
    val note: String = "",
    val account: String = "",
    val source: Source = Source.MANUAL,
    val pendingConfirmation: Boolean = false,
    val internalTransfer: Boolean = false,
    val dedupeKey: String? = null,
    val refundOfId: Long? = null
) { init { require(amountMinor > 0); require(currency.length == 3) } }

data class DateRange(val start: LocalDate, val endInclusive: LocalDate, val zone: ZoneId) {
    init { require(!endInclusive.isBefore(start)) }
    fun contains(instant: Instant) = !instant.atZone(zone).toLocalDate().isBefore(start) &&
        !instant.atZone(zone).toLocalDate().isAfter(endInclusive)
}

data class CategoryTotal(val category: String, val amountMinor: Long)
data class StatisticsSummary(val totalMinor: Long, val categories: List<CategoryTotal>)

object StatisticsEngine {
    fun summarize(rows: List<Transaction>, range: DateRange, direction: Direction): StatisticsSummary {
        val eligible = rows.filter { !it.internalTransfer && it.direction == direction && range.contains(it.occurredAt) }
        val categories = eligible.groupingBy { it.category }.fold(0L) { total, row -> total + row.amountMinor }
            .map { CategoryTotal(it.key, it.value) }.sortedByDescending { it.amountMinor }
        return StatisticsSummary(categories.sumOf { it.amountMinor }, categories)
    }
}
```

- [ ] **Step 4: Run all domain tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StatisticsEngineTest"`  
Expected: PASS for income, expense, internal transfer, and date boundary cases.

- [ ] **Step 5: Commit domain rules**

```powershell
git add app/src/main/java/com/local/autobookkeeping/domain app/src/test/java/com/local/autobookkeeping/domain
git commit -m "feat: add ledger domain and statistics rules"
```

### Task 3: Room Ledger, Categories, Accounts, and Merchant Rules

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/data/db/Entities.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/data/db/TransactionDao.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/data/db/AppDatabase.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/data/LedgerRepository.kt`
- Test: `app/src/androidTest/java/com/local/autobookkeeping/data/LedgerRepositoryTest.kt`

**Interfaces:**
- Consumes: `Transaction` from Task 2.
- Produces: `LedgerRepository.observeTransactions(): Flow<List<Transaction>>`, `save`, `delete`, `findByDedupeKey`, and `replaceAllInTransaction`.

- [ ] **Step 1: Write a failing in-memory Room repository test**

```kotlin
@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    @Test fun saveEditDeleteAndDedupe() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val repo = RoomLedgerRepository(db)
        val id = repo.save(sampleTransaction(dedupeKey = "wx-2800-1230"))
        assertNotNull(repo.findByDedupeKey("wx-2800-1230"))
        repo.delete(id)
        assertNull(repo.findByDedupeKey("wx-2800-1230"))
        db.close()
    }
}
```

- [ ] **Step 2: Verify the repository test fails**

Run: `./gradlew.bat :app:connectedDebugAndroidTest --tests "*LedgerRepositoryTest"`  
Expected: FAIL because Room entities and repository do not exist.

- [ ] **Step 3: Implement schema version 1 and repository**

```kotlin
@Entity(tableName = "transactions", indices = [Index(value = ["dedupeKey"], unique = true)])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMinor: Long, val currency: String, val direction: String, val categoryId: Long,
    val occurredAtEpochMillis: Long, val merchant: String, val note: String, val accountId: Long?,
    val source: String, val pendingConfirmation: Boolean, val internalTransfer: Boolean,
    val dedupeKey: String?, val redactedNotificationDigest: String?, val refundOfId: Long?,
    val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long
)

@Dao interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC") fun observeAll(): Flow<List<TransactionEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: TransactionEntity): Long
    @Update suspend fun update(row: TransactionEntity)
    @Query("DELETE FROM transactions WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("SELECT * FROM transactions WHERE dedupeKey = :key LIMIT 1") suspend fun findByDedupeKey(key: String): TransactionEntity?
}
```

Add `CategoryEntity`, `AccountEntity`, and `MerchantRuleEntity`, seed the approved built-in categories on database creation, and implement explicit entity/domain mapping functions. Make `replaceAllInTransaction` a `@Transaction` database operation so restore cannot leave partial data.

- [ ] **Step 4: Run Room tests and export schema**

Run: `./gradlew.bat :app:connectedDebugAndroidTest --tests "*LedgerRepositoryTest"`  
Expected: PASS and `app/schemas/.../1.json` created.

- [ ] **Step 5: Commit persistence**

```powershell
git add app/src app/schemas
git commit -m "feat: persist ledger data with Room"
```

### Task 4: Payment Notification Parsers and Deduplication

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/notification/PaymentParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/WechatParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/AlipayParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/BankNotificationParser.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/CandidateProcessor.kt`
- Test: `app/src/test/java/com/local/autobookkeeping/notification/PaymentParserTest.kt`

**Interfaces:**
- Produces: `PaymentCandidate(amountMinor, direction, merchant, occurredAt, sourcePackage, confidence, redactedDigest, dedupeKey)`.
- Produces: `PaymentParser.parse(NotificationText): PaymentCandidate?`.
- Consumes: `LedgerRepository.findByDedupeKey` and `save` from Task 3.

- [ ] **Step 1: Add anonymized parser fixtures and failing tests**

```kotlin
class PaymentParserTest {
    @Test fun `wechat expense parses yuan into minor units`() {
        val candidate = WechatParser.parse(NotificationText("微信支付", "支付成功 ¥28.00", "com.tencent.mm", instant))
        assertEquals(2800, candidate?.amountMinor)
        assertEquals(Direction.EXPENSE, candidate?.direction)
    }

    @Test fun `unknown notification returns null without storing raw text`() {
        assertNull(AlipayParser.parse(NotificationText("服务通知", "你有一条消息", "com.eg.android.AlipayGphone", instant)))
    }

    @Test fun `allowlisted bank debit notification parses as expense`() {
        val parser = BankNotificationParser(setOf("com.example.bank"))
        val candidate = parser.parse(NotificationText("账户变动", "消费人民币¥68.50", "com.example.bank", instant))
        assertEquals(6850, candidate?.amountMinor)
        assertEquals(Direction.EXPENSE, candidate?.direction)
    }
}
```

- [ ] **Step 2: Run parser tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*PaymentParserTest"`  
Expected: FAIL because parser types do not exist.

- [ ] **Step 3: Implement strict parsing and SHA-256 dedupe**

```kotlin
data class NotificationText(val title: String, val body: String, val packageName: String, val postedAt: Instant)
data class PaymentCandidate(
    val amountMinor: Long, val direction: Direction?, val merchant: String?, val occurredAt: Instant,
    val sourcePackage: String, val confidence: Int, val redactedDigest: String, val dedupeKey: String
)
fun interface PaymentParser { fun parse(notification: NotificationText): PaymentCandidate? }

internal fun parseCnyMinor(text: String): Long? = Regex("(?:¥|￥)\\s*([0-9]+(?:\\.[0-9]{1,2})?)")
    .find(text)?.groupValues?.get(1)?.let { BigDecimal(it).movePointRight(2).longValueExact() }
```

Use allowlisted package names and source-specific keywords. `BankNotificationParser` accepts only bank packages explicitly enabled in Settings and recognizes separate debit/expense and credit/income keyword sets; ambiguous bank messages become pending candidates. Hash package, amount, normalized title, and a two-minute time bucket for `dedupeKey`; hash a redacted representation rather than retaining notification text. Direction or merchant uncertainty sets confidence below the direct-save threshold and produces a pending candidate.

- [ ] **Step 4: Run parser and candidate processor tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*notification*"`  
Expected: PASS for WeChat, Alipay, allowlisted bank expense/income, malformed amount, unknown format, disabled package, and duplicate cases.

- [ ] **Step 5: Commit parsers**

```powershell
git add app/src/main/java/com/local/autobookkeeping/notification app/src/test/java/com/local/autobookkeeping/notification
git commit -m "feat: parse and deduplicate payment notifications"
```

### Task 5: Notification Listener, Permission State, and Pending Review

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/PaymentNotificationService.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/notification/NotificationAccess.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/data/SettingsRepository.kt`
- Test: `app/src/test/java/com/local/autobookkeeping/notification/NotificationAccessTest.kt`

**Interfaces:**
- Consumes: parsers and `CandidateProcessor` from Task 4.
- Produces: `NotificationAccess.isEnabled(context): Boolean` and DataStore flows for enabled sources.

- [ ] **Step 1: Write failing permission-state and source-filter tests**

```kotlin
@Test fun `disabled source is ignored`() = runTest {
    val settings = FakeSettingsRepository(enabledPackages = emptySet())
    val router = NotificationRouter(settings, listOf(WechatParser, AlipayParser))
    assertNull(router.route(wechatExpenseFixture))
}
```

- [ ] **Step 2: Verify test failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*NotificationAccessTest"`  
Expected: FAIL because source settings and router are missing.

- [ ] **Step 3: Register and implement the listener**

```xml
<service
    android:name=".notification.PaymentNotificationService"
    android:label="自动记账通知服务"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

```kotlin
class PaymentNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        applicationScope.launch {
            val extras = sbn.notification.extras
            val input = NotificationText(
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                sbn.packageName,
                Instant.ofEpochMilli(sbn.postTime)
            )
            applicationGraph.notificationRouter.route(input)?.let(applicationGraph.candidateProcessor::process)
        }
    }
}
```

Direct-save only when confidence meets the tested threshold; otherwise save with `pendingConfirmation = true`. Send a local confirmation notification without merchant details on the lock screen.

- [ ] **Step 4: Run unit tests and install a debug build for manual permission verification**

Run: `./gradlew.bat :app:testDebugUnitTest :app:installDebug`  
Expected: tests pass, app installs, and Android Settings lists “自动记账通知服务” under notification access.

- [ ] **Step 5: Commit notification integration**

```powershell
git add app/src
git commit -m "feat: capture payment notifications with user consent"
```

### Task 6: Home, Ledger, and Manual/Edit Transaction Screens

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/home/HomeScreen.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/ledger/LedgerViewModel.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/ledger/LedgerScreen.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryScreen.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
- Test: `app/src/androidTest/java/com/local/autobookkeeping/LedgerFlowTest.kt`

**Interfaces:**
- Consumes: repository flows from Task 3 and permission state from Task 5.
- Produces: `EntryDraft` and `EntryViewModel.save(): Result<Long>` for both manual and auto-created rows.

- [ ] **Step 1: Write a failing manual create/edit/delete UI test**

```kotlin
@Test fun createEditAndDeleteExpense() {
    compose.onNodeWithContentDescription("记一笔").performClick()
    compose.onNodeWithTag("amount").performTextInput("28.00")
    compose.onNodeWithText("餐饮").performClick()
    compose.onNodeWithText("保存记录").performClick()
    compose.onNodeWithText("账单").performClick()
    compose.onNodeWithText("¥28.00").assertIsDisplayed().performClick()
    compose.onNodeWithText("删除记录").performClick()
    compose.onNodeWithText("确认删除").performClick()
    compose.onNodeWithText("¥28.00").assertDoesNotExist()
}
```

- [ ] **Step 2: Run the UI flow test and verify failure**

Run: `./gradlew.bat :app:connectedDebugAndroidTest --tests "*LedgerFlowTest"`  
Expected: FAIL because the three feature screens are placeholders.

- [ ] **Step 3: Implement screen state and validation**

```kotlin
data class EntryDraft(
    val id: Long? = null, val amountText: String = "", val direction: Direction = Direction.EXPENSE,
    val category: String = "餐饮", val account: String = "", val occurredAt: Instant = Instant.now(),
    val merchant: String = "", val note: String = "", val internalTransfer: Boolean = false
)

fun EntryDraft.toTransaction(): Transaction {
    val minor = amountText.toBigDecimalOrNull()?.movePointRight(2)?.longValueExact()
        ?: throw IllegalArgumentException("请输入有效金额")
    return Transaction(id ?: 0, minor, "CNY", direction, category, occurredAt, merchant, note, account,
        internalTransfer = internalTransfer)
}
```

Implement the approved compact typography, flat bottom add button, today totals, recent rows, pending banner, grouped ledger rows, search, month/direction/category/account filters, edit fields, internal-transfer switch, and destructive delete confirmation. Use `collectAsStateWithLifecycle()` and stable list keys.

- [ ] **Step 4: Run UI tests and unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest --tests "*LedgerFlowTest"`  
Expected: PASS and the created transaction disappears after confirmed deletion.

- [ ] **Step 5: Commit ledger UI**

```powershell
git add app/src
git commit -m "feat: add home ledger and transaction editor"
```

### Task 7: Date-Selectable Income and Expense Statistics

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/ui/statistics/StatisticsViewModel.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/statistics/StatisticsScreen.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/statistics/PieChart.kt`
- Test: `app/src/test/java/com/local/autobookkeeping/ui/statistics/StatisticsViewModelTest.kt`
- Test: `app/src/androidTest/java/com/local/autobookkeeping/StatisticsScreenTest.kt`

**Interfaces:**
- Consumes: `StatisticsEngine` and repository flow.
- Produces: `StatisticsUiState(rangeMode, range, direction, summary)` and category drill-down callbacks.

- [ ] **Step 1: Write failing tests for daily, weekly, monthly, yearly, custom, and direction switching**

```kotlin
@Test fun `custom range and income direction update summary`() = runTest {
    val vm = StatisticsViewModel(FakeLedgerRepository(sampleRows), clock, zone)
    vm.selectCustom(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 22))
    vm.selectDirection(Direction.INCOME)
    assertEquals(30000, vm.state.value.summary.totalMinor)
    assertTrue(vm.state.value.summary.categories.all { it.amountMinor > 0 })
}
```

- [ ] **Step 2: Verify statistics tests fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*StatisticsViewModelTest"`  
Expected: FAIL because statistics UI state is missing.

- [ ] **Step 3: Implement range controls, accessible pie chart, ranking, and drill-down**

```kotlin
enum class RangeMode { DAY, WEEK, MONTH, YEAR, CUSTOM }
data class StatisticsUiState(
    val rangeMode: RangeMode, val range: DateRange, val direction: Direction,
    val summary: StatisticsSummary = StatisticsSummary(0, emptyList())
)

@Composable
fun PieChart(items: List<CategoryTotal>, modifier: Modifier = Modifier) {
    val total = items.sumOf { it.amountMinor }.coerceAtLeast(1)
    Canvas(modifier.semantics { contentDescription = items.joinToString { "${it.category}${it.amountMinor}" } }) {
        var start = -90f
        items.forEachIndexed { index, item ->
            val sweep = item.amountMinor.toFloat() / total * 360f
            drawArc(chartColors[index % chartColors.size], start, sweep, true)
            start += sweep
        }
    }
}
```

Show empty-state copy instead of a fake chart when total is zero. Date arrows move by the selected unit; tapping the date opens a date or date-range picker. Tapping a legend/ranking row opens the ledger filtered to that category and range.

- [ ] **Step 4: Run statistics tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*Statistics*" :app:connectedDebugAndroidTest --tests "*StatisticsScreenTest"`  
Expected: PASS for all five range modes, both directions, internal-transfer exclusion, empty state, and category drill-down.

- [ ] **Step 5: Commit statistics**

```powershell
git add app/src
git commit -m "feat: add selectable income and expense statistics"
```

### Task 8: Three Themes, Settings, Categories, and Accounts

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/ui/theme/AppTheme.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/data/SettingsRepository.kt`
- Test: `app/src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTest.kt`

**Interfaces:**
- Produces: `enum class ThemeStyle { CLEAR_MINT, RECEIPT_LEDGER, PAPER_MINIMAL }`.
- Produces: `SettingsRepository.theme: Flow<ThemeStyle>` and `setTheme(style)`.

- [ ] **Step 1: Write a failing theme persistence test**

```kotlin
@Test fun switchThemeWithoutLosingCurrentScreen() {
    compose.onNodeWithText("统计").performClick()
    compose.onNodeWithText("设置").performClick()
    compose.onNodeWithText("外观").performClick()
    compose.onNodeWithText("纸感极简").performClick()
    compose.activityRule.scenario.recreate()
    compose.onNodeWithTag("theme-PAPER_MINIMAL").assertExists()
}
```

- [ ] **Step 2: Run the theme test and verify failure**

Run: `./gradlew.bat :app:connectedDebugAndroidTest --tests "*ThemeSwitchTest"`  
Expected: FAIL because theme selection is not implemented.

- [ ] **Step 3: Implement theme tokens and settings management**

```kotlin
enum class ThemeStyle { CLEAR_MINT, RECEIPT_LEDGER, PAPER_MINIMAL }

@Composable
fun AutoBookkeepingTheme(style: ThemeStyle, content: @Composable () -> Unit) {
    val colors = when (style) {
        ThemeStyle.CLEAR_MINT -> clearMintColors
        ThemeStyle.RECEIPT_LEDGER -> receiptColors
        ThemeStyle.PAPER_MINIMAL -> paperMinimalColors
    }
    MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
}
```

Apply visual differences through theme tokens and focused card components, not duplicated feature screens. Add source toggles, notification-access shortcut, theme picker, category add/rename/reorder/hide, account add/reorder/hide, privacy information, and clear-data confirmation.

- [ ] **Step 4: Run settings and theme tests**

Run: `./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest --tests "*ThemeSwitchTest"`  
Expected: PASS; selected theme survives activity recreation and ledger data remains unchanged.

- [ ] **Step 5: Commit themes and settings**

```powershell
git add app/src
git commit -m "feat: add themes and local settings"
```

### Task 9: Versioned Local Backup and Safe Restore

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/backup/BackupManager.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/backup/BackupDocument.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/settings/SettingsScreen.kt`
- Test: `app/src/test/java/com/local/autobookkeeping/backup/BackupManagerTest.kt`

**Interfaces:**
- Consumes: repository export and transactional replace APIs.
- Produces: `BackupManager.export(output: OutputStream)` and `restore(input: InputStream): RestoreResult`.

- [ ] **Step 1: Write failing round-trip and corrupt-file tests**

```kotlin
@Test fun `corrupt backup never overwrites current ledger`() = runTest {
    val repo = FakeBackupRepository(existing = sampleRows)
    val result = BackupManager(repo, json).restore("not-json".byteInputStream())
    assertTrue(result is RestoreResult.Invalid)
    assertEquals(sampleRows, repo.current())
}
```

- [ ] **Step 2: Run backup tests and verify failure**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*BackupManagerTest"`  
Expected: FAIL because backup types do not exist.

- [ ] **Step 3: Implement versioned validation and transactional replacement**

```kotlin
@Serializable data class BackupDocument(
    val formatVersion: Int = 1,
    val exportedAtEpochMillis: Long,
    val transactions: List<BackupTransaction>,
    val categories: List<BackupCategory>,
    val accounts: List<BackupAccount>,
    val merchantRules: List<BackupMerchantRule>,
    val theme: String
)
sealed interface RestoreResult { data class Success(val count: Int) : RestoreResult; data class Invalid(val reason: String) : RestoreResult }
```

Parse into memory, require `formatVersion == 1`, validate positive amounts and valid references, make a safety copy, then call the single Room transaction replacement. Use Android Storage Access Framework `CreateDocument("application/json")` and `OpenDocument()`; never request broad storage permission.

- [ ] **Step 4: Run backup tests**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "*BackupManagerTest"`  
Expected: PASS for round trip, invalid JSON, unsupported version, and broken references.

- [ ] **Step 5: Commit backup support**

```powershell
git add app/src
git commit -m "feat: add safe local backup and restore"
```

### Task 10: End-to-End Verification and Debug APK

**Files:**
- Create: `app/src/androidTest/java/com/local/autobookkeeping/EndToEndTest.kt`
- Create: `docs/testing/manual-android-checklist.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: the complete application.
- Produces: a reproducible debug APK and documented manual notification checks.

- [ ] **Step 1: Add the end-to-end test**

```kotlin
@Test fun completePersonalLedgerJourney() {
    createManualExpense("28.00", "红包")
    createManualIncome("100.00", "红包")
    createInternalTransfer("500.00")
    openStatistics(Direction.EXPENSE, RangeMode.MONTH)
    assertPieContains("红包", "¥28.00")
    openStatistics(Direction.INCOME, RangeMode.MONTH)
    assertPieContains("红包", "¥100.00")
    assertTextAbsent("¥500.00")
    switchTheme("小票账本")
    assertLedgerStillContains("¥28.00")
}
```

- [ ] **Step 2: Run the entire automated suite**

Run: `./gradlew.bat clean :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug`  
Expected: `BUILD SUCCESSFUL`, zero failed tests, zero lint errors, and `app/build/outputs/apk/debug/app-debug.apk` created.

- [ ] **Step 3: Perform the real-device privacy and notification checklist**

Record results for: first-run permission explanation; denied permission; enabled permission; WeChat expense; Alipay expense; supported income; duplicate notification; unknown notification; pending confirmation; notification access revoked; process restart; device restart; all three themes; 200% system font; backup export; valid restore; corrupt restore; and clear-data confirmation.

- [ ] **Step 4: Update user documentation**

Document Android 8.0+ requirement, local-only privacy model, notification limitations, how to enable notification access, internal-transfer behavior, backup steps, debug APK location, and the exact verification command from Step 2.

- [ ] **Step 5: Commit the verified MVP**

```powershell
git add app/src/androidTest docs/testing README.md
git commit -m "test: verify Android bookkeeping MVP"
```

---

## Final Acceptance Gate

Do not claim completion until the full Task 10 command succeeds on the configured emulator and the real-device checklist has no unresolved data-loss, privacy, notification, or statistics defect. Parser failures caused by unsupported notification formats must appear as pending/diagnostic results, never as silently incorrect confirmed transactions.
