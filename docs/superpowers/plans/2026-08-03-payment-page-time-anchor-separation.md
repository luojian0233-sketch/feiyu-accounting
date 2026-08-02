# Payment Page Time Anchor Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent shared page/detail headings from authorizing unlabeled page timestamps as transaction times while preserving valid transaction-local full, month-day, and clock timestamps.

**Architecture:** Keep the existing bounded card-container and geometry logic unchanged. Split semantic responsibilities only at timestamp authorization: page/platform/detail headings remain card-grouping anchors, while transaction event signals alone may authorize an unlabeled timestamp before the selected amount; labeled timestamps and timestamps following the selected amount retain their existing behavior.

**Tech Stack:** Kotlin 2.3.10, Android SDK 26–37, JUnit 5, existing `PaymentPageParser` pure JVM tests, Gradle Wrapper 9.5.0.

## Global Constraints

- Resume blocked Task 4 only; do not implement Task 5 or later work.
- Do not change card partitioning, geometry thresholds, amount selection, merchant extraction, account extraction, event/direction mapping, package restrictions, database schema, backup format, or digest format.
- Page/platform/detail headings such as `微信支付`, `支付宝`, `账单详情`, `账单明细`, and `交易详情` may group cards but must never authorize an unlabeled timestamp.
- Exact recognized time-field labels (`交易时间`, `支付时间`, `转账时间`, `收款时间`, `退款时间`, `创建时间`) continue to authorize their timestamp.
- An unlabeled timestamp may be owned only when it follows a transaction event anchor in the same card with horizontal overlap, or follows the selected amount in the same card with horizontal overlap.
- Unowned timestamps are ignored and the candidate uses `snapshot.observedAt`; they are not extraction errors.
- Raw page text and timestamp source text must not enter logs, exceptions, receipts, or evidence-digest input.
- Run every Gradle command from `R:\` with `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `ANDROID_HOME=E:\软件开发\DevTools\Android\sdk`, `ANDROID_SDK_ROOT=E:\软件开发\DevTools\Android\sdk`, `GRADLE_USER_HOME=C:\Users\35020\.gradle`, and `--max-workers=1`.

---

### Task 1: Separate Page Grouping Anchors from Transaction Time Anchors

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/PaymentPageParser.kt:716-737`
- Test: `app/src/test/java/com/local/autobookkeeping/recognition/PaymentPageParserTest.kt:510-550`

**Interfaces:**
- Consumes: `selectVisibleTime(elements, amountElement, observedAt, zone)`, `isTimeOwnedByCard(match, timeElement, cardElements, amountElement)`, `EVENT_SIGNALS`, `TIME_FIELD_LABELS`, and the existing bounded card groups supplied to `parseCard`.
- Produces: `isTransactionTimeAnchor(element: ObservedTextElement): Boolean`, which returns `true` only when the element contains an explicit key from `EVENT_SIGNALS`.
- Preserves: `CARD_DETAIL_SIGNATURES` and `TRANSACTION_DETAIL_SIGNATURES` for grouping and page metadata; they are not deleted or repurposed.

- [ ] **Step 1: Add the failing shared-heading timestamp regression**

Add this test to `PaymentPageParserTest` next to the existing page-header time test:

```kotlin
@Test
fun `shared detail heading cannot authorize a bare timestamp above side by side events`() {
    listOf("2026-07-20 08:00", "07-20 08:00").forEach { pageTime ->
        val outcome = GenericBillParser.parse(
            PageSnapshot(
                packageName = null,
                elements = listOf(
                    ObservedTextElement("账单详情", TextBounds(40, 40, 600, 60)),
                    ObservedTextElement(pageTime, TextBounds(40, 70, 600, 90)),
                    ObservedTextElement("转出", TextBounds(40, 120, 280, 140)),
                    ObservedTextElement("实付 ¥5.00", TextBounds(40, 160, 280, 180)),
                    ObservedTextElement("转入", TextBounds(320, 120, 560, 140)),
                    ObservedTextElement("实付 ¥6.00", TextBounds(320, 160, 560, 180)),
                ),
                observedAt = OBSERVED_AT,
            ),
        ) as ExtractionOutcome.Multiple

        assertEquals(listOf(500L, 600L), outcome.options.map { it.amountMinor }, pageTime)
        assertEquals(
            listOf(Direction.EXPENSE, Direction.INCOME),
            outcome.options.map { it.direction },
            pageTime,
        )
        assertEquals(
            listOf(OBSERVED_AT, OBSERVED_AT),
            outcome.options.map { it.occurredAt },
            pageTime,
        )
    }
}
```

This single test covers both the full-timestamp and month-day-timestamp forms in the exact previously failing layout.

- [ ] **Step 2: Run the targeted test and verify the expected RED result**

Run from `R:\`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='E:\软件开发\DevTools\Android\sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME='C:\Users\35020\.gradle'
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.PaymentPageParserTest" --no-daemon --console=plain --max-workers=1
```

Expected: `PaymentPageParserTest` fails only in the new test because the left candidate uses `2026-07-20T00:00:00Z` instead of `OBSERVED_AT`; existing tests remain green.

- [ ] **Step 3: Implement the minimal semantic-role separation**

In `isTimeOwnedByCard`, change the transaction-anchor predicate call:

```kotlin
val followsTransactionAnchor = cardElements.any { anchor ->
    isTransactionTimeAnchor(anchor) &&
        timeElement.bounds.top >= anchor.bounds.top &&
        horizontalOverlap(timeElement.bounds, anchor.bounds) > 0
}
```

Replace the current mixed-role predicate with an event-only predicate:

```kotlin
private fun isTransactionTimeAnchor(element: ObservedTextElement): Boolean {
    val text = element.text.trim()
    return EVENT_SIGNALS.keys.any(text::contains)
}
```

Do not modify `TRANSACTION_DETAIL_SIGNATURES`, `CARD_DETAIL_SIGNATURES`, `buildSemanticAnchorClusters`, `assignElementsToBoundedContainers`, or any distance constant.

- [ ] **Step 4: Run the targeted parser suite and verify GREEN**

Run the same targeted command from Step 2.

Expected: all `PaymentPageParserTest` tests pass, including:

- shared full timestamp above side-by-side events falls back to `OBSERVED_AT` for both candidates;
- shared month-day timestamp does the same;
- visible full timestamp inside a transaction card remains valid;
- visible month-day timestamp remains valid;
- labeled local clock remains valid;
- system clock and remote-card timestamp regressions remain green.

- [ ] **Step 5: Run recognition and notification regression suites**

Run:

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests "com.local.autobookkeeping.recognition.*" `
  --tests "com.local.autobookkeeping.notification.*" `
  --no-daemon --console=plain --max-workers=1
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 6: Run the complete JVM regression suite**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon --console=plain --max-workers=1
```

Expected: `BUILD SUCCESSFUL` with zero failures, errors, or skipped tests.

- [ ] **Step 7: Verify privacy and scope**

Run:

```powershell
git diff --check
git diff --stat
Get-ChildItem -LiteralPath 'app\src\main\java\com\local\autobookkeeping\recognition' -Filter '*.kt' |
  Select-String -Pattern 'Log\.|println\(|printStackTrace\(|System\.out|System\.err'
```

Expected:

- `git diff --check` prints nothing;
- only `PaymentPageParser.kt` and `PaymentPageParserTest.kt` changed;
- the source scan prints no newly introduced logging/output path;
- no raw page text was added to exceptions, receipts, or digest inputs.

- [ ] **Step 8: Commit the repair**

```powershell
git add -- `
  'app/src/main/java/com/local/autobookkeeping/recognition/PaymentPageParser.kt' `
  'app/src/test/java/com/local/autobookkeeping/recognition/PaymentPageParserTest.kt'
git diff --cached --check
git commit -m "fix: separate page and transaction time anchors"
```

Expected: one focused commit containing the failing regression and minimal event-only time-anchor change.
