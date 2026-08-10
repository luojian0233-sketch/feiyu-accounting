# Opaque Payment WebView OCR Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make authorized微信/支付宝 payment WebViews with an empty accessibility text tree enter the existing in-memory screenshot OCR pipeline without broadening persistence or package scope.

**Architecture:** Keep `PaymentPageAccessibilityService` and the existing capture/OCR/parser/coordinator chain unchanged. Extend `AutoPageRecognitionController` with a second bounded `RecognitionDebouncer` used only when an allowed, enabled platform snapshot has zero text elements; a normal return commits a two-second cooldown, while cancellation releases the reservation. OCR output still has to pass the existing platform parser before the coordinator sees a candidate.

**Tech Stack:** Kotlin, Android AccessibilityService, coroutines, ML Kit local OCR, JUnit/Robolectric, Gradle, ADB on PKG110 API 36.

## Global Constraints

- Only `com.tencent.mm` and `com.eg.android.AlipayGphone` may use the opaque-page fallback.
- Automatic bookkeeping master, matching source toggle, and Android accessibility access must all remain mandatory.
- Empty-page cooldown is exactly 2,000 ms and storage is bounded to 64 entries.
- Screenshots, accessibility text, OCR text, notification bodies, and page raw text must never be written to files, Room, preferences, logs, or notifications.
- OCR must still satisfy the existing platform payment signature, amount, and direction rules; rejection creates no placeholder transaction.
- Bitmap recycling, cancellation propagation, coordinator deduplication, and fail-closed package validation remain unchanged.
- Do not add network, storage, overlay, gesture, or new package permissions.
- Do not run `connectedDebugAndroidTest` against the user's personal-data installation.
- Every direct ADB command has a 10-second timeout; do not restart ADB, clear app data, uninstall the app, or disconnect the phone.

---

## File Map

- Modify `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt`: route only empty allowed snapshots to the existing OCR fallback and own the independent 2-second/64-entry debounce reservation.
- Modify `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt`: prove empty微信/支付宝 behavior, fail-closed gates, cooldown, nonpayment rejection, retry, cancellation, and bitmap ownership.
- Verify `app/src/test/java/com/local/autobookkeeping/recognition/PrivacyContractTest.kt`: existing source contract must continue to reject screenshot/OCR persistence APIs; no production edit is planned.
- Modify `docs/testing/manual-android-checklist.md`: record the fix build, replacement-install preservation result, reused ¥1.00 detail-page result, and duplicate-page result.
- Update ignored `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/progress.md`: record Task 14 root cause and device evidence without forcing the ignored file into Git.

### Task 1: Route Empty Payment WebViews Through Bounded OCR

**Files:**
- Modify: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt`

**Interfaces:**
- Consumes: `PageSnapshot`, `RecognitionDebouncer.reserve/commit/release`, `AccessibilityCaptureGateway.capture()`, `LocalTextRecognizer.recognize(Bitmap)`, and the existing `WechatPageParser` / `AlipayPageParser`.
- Produces: unchanged public `suspend fun AutoPageRecognitionController.onWindowChanged(snapshot: PageSnapshot)` behavior plus a private opaque-page path using `RecognitionDebouncer(windowMillis = 2_000, maxEntries = 64)`.

- [ ] **Step 1: Add the微信 empty-tree RED test**

Add this test to `AutoPageRecognitionControllerTest`:

```kotlin
@Test
fun `empty WeChat WebView uses one in-memory OCR fallback`() = runTest {
    var screenshotCalls = 0
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    gateway.attach {
        screenshotCalls += 1
        CaptureResult.Success(bitmap, WECHAT_PACKAGE)
    }

    controller.onWindowChanged(page(WECHAT_PACKAGE))

    assertEquals(1, screenshotCalls)
    assertEquals(1, ocrCalls)
    assertEquals(1, processor.calls)
    assertTrue(bitmap.isRecycled)
}
```

- [ ] **Step 2: Add the支付宝 empty-tree and fail-closed RED tests**

Add these tests:

```kotlin
@Test
fun `empty Alipay WebView uses the Alipay parser after OCR`() = runTest {
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    gateway.attach { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) }
    val alipayRecognizer = LocalTextRecognizer {
        listOf(
            element("支付宝", 0),
            element("支付成功", 40),
            element("¥1.00", 80),
        )
    }

    controller(alipayRecognizer).onWindowChanged(page(ALIPAY_PACKAGE))

    assertEquals(1, processor.calls)
    assertTrue(bitmap.isRecycled)
}

@Test
fun `disabled empty platform stays outside capture and OCR`() = runTest {
    enabled = false
    var screenshotCalls = 0
    gateway.attach {
        screenshotCalls += 1
        error("disabled empty page must not be captured")
    }

    controller.onWindowChanged(page(WECHAT_PACKAGE))

    assertEquals(1, settingsCalls)
    assertEquals(0, screenshotCalls)
    assertEquals(0, ocrCalls)
    assertEquals(0, processor.calls)
}
```

The existing `controller ignores packages outside WeChat and Alipay` and `unsigned rejected page never invokes screenshot fallback` tests remain unchanged and cover package and ordinary nonempty-page gates.

- [ ] **Step 3: Add deterministic cooldown and rejection RED tests**

Extend the test helper with exact constructor inputs:

```kotlin
private fun controller(
    recognizer: LocalTextRecognizer,
    settings: AutomaticPageRecognitionSettings = AutomaticPageRecognitionSettings {
        settingsCalls += 1
        enabled
    },
    candidateProcessor: RecognitionCandidateProcessor = processor,
    debouncer: RecognitionDebouncer = RecognitionDebouncer(windowMillis = 1_000),
    opaquePageDebouncer: RecognitionDebouncer = RecognitionDebouncer(
        windowMillis = 2_000,
        maxEntries = 64,
    ),
    elapsedRealtimeMillis: () -> Long = { 10_000L },
) = AutoPageRecognitionController(
    settings = settings,
    captureGateway = gateway,
    textRecognizer = recognizer,
    candidateProcessor = candidateProcessor,
    diagnostics = diagnostics,
    debouncer = debouncer,
    opaquePageDebouncer = opaquePageDebouncer,
    elapsedRealtimeMillis = elapsedRealtimeMillis,
)
```

Add the cooldown test:

```kotlin
@Test
fun `empty nonpayment WebView is captured once per two second cooldown`() = runTest {
    var now = 10_000L
    var screenshotCalls = 0
    val bitmaps = mutableListOf<Bitmap>()
    gateway.attach {
        screenshotCalls += 1
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmaps += bitmap
            CaptureResult.Success(bitmap, WECHAT_PACKAGE)
        }
    }
    val nonpaymentRecognizer = LocalTextRecognizer {
        listOf(element("普通网页", 0))
    }
    val opaqueController = controller(
        recognizer = nonpaymentRecognizer,
        elapsedRealtimeMillis = { now },
    )

    opaqueController.onWindowChanged(page(WECHAT_PACKAGE))
    opaqueController.onWindowChanged(page(WECHAT_PACKAGE))
    assertEquals(1, screenshotCalls)
    assertEquals(0, processor.calls)

    now += 2_000
    opaqueController.onWindowChanged(page(WECHAT_PACKAGE))

    assertEquals(2, screenshotCalls)
    assertEquals(0, processor.calls)
    assertTrue(bitmaps.all(Bitmap::isRecycled))
}
```

Add a capture-failure test that calls the empty snapshot twice at `10_000L`, advances to `12_000L`, calls again, and asserts two capture attempts, `CAPTURE_FAILED`, and zero processor calls. Add a cancellation test whose recognizer throws `CancellationException`, asserts propagation, then immediately retries and asserts a second capture; this proves cancellation releases rather than commits the opaque reservation.

- [ ] **Step 4: Run the focused test class and verify RED**

Run from `E:\软件开发\自动记账App\.worktrees\android-mvp`:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='E:\软件开发\DevTools\Android\sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
.\gradlew.bat --no-daemon --max-workers=1 --console=plain testDebugUnitTest --tests "com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionControllerTest"
```

Expected: the new empty-tree tests fail because current `NOT_PAYMENT_PAGE` handling returns before `captureGateway.capture()`; existing tests remain green. Use a 60-second watchdog, terminate the exact command at timeout, and report the last Gradle task instead of polling an old process.

- [ ] **Step 5: Add the independent opaque-page debouncer**

Change the internal primary constructor in `AutoPageRecognitionController.kt` to accept:

```kotlin
private val opaquePageDebouncer: RecognitionDebouncer = RecognitionDebouncer(
    windowMillis = OPAQUE_PAGE_WINDOW_MILLIS,
    maxEntries = OPAQUE_PAGE_MAX_ENTRIES,
),
```

Add exact companion constants:

```kotlin
private const val OPAQUE_PAGE_WINDOW_MILLIS = 2_000L
private const val OPAQUE_PAGE_MAX_ENTRIES = 64
```

Do not replace or widen the existing normal-page debouncer.

- [ ] **Step 6: Route only empty rejected snapshots to OCR**

Replace the current early `NOT_PAYMENT_PAGE` return in `onWindowChanged` with:

```kotlin
if (
    parsed is ExtractionOutcome.Rejected &&
    parsed.reason == RecognitionRejectReason.NOT_PAYMENT_PAGE
) {
    if (snapshot.elements.isEmpty()) {
        processOpaquePageFallback(snapshot, parser)
    }
    return
}
```

Keep `parserFor(...)` and `isEnabled(...)` before this branch so unsupported or disabled packages never capture.

- [ ] **Step 7: Implement reservation ownership and cooldown**

Add this private method:

```kotlin
private suspend fun processOpaquePageFallback(
    snapshot: PageSnapshot,
    parser: PaymentPageParser,
) {
    val reservation = opaquePageDebouncer.reserve(
        snapshot,
        elapsedRealtimeMillis(),
    ) ?: return
    var completed = false
    try {
        processSingleOcrFallback(snapshot, parser)
        completed = true
    } finally {
        if (completed) {
            opaquePageDebouncer.commit(reservation, elapsedRealtimeMillis())
        } else {
            opaquePageDebouncer.release(reservation)
        }
    }
}
```

This commits normal capture failure, OCR failure, and parser rejection for exactly two seconds; a thrown cancellation or unexpected exception releases the reservation. Reuse `processSingleOcrFallback` and `processCapturedBitmap` so package validation and Bitmap recycling remain single-owner.

- [ ] **Step 8: Run focused GREEN and related recognition tests**

Run:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain testDebugUnitTest --tests "com.local.autobookkeeping.recognition.accessibility.*" --tests "com.local.autobookkeeping.recognition.PrivacyContractTest" --tests "com.local.autobookkeeping.recognition.RecognitionCoordinatorTest"
```

Expected: all selected tests pass, including new empty-tree cases and existing signed-page, package, cancellation, capture, OCR, privacy, and duplicate tests. Use a 120-second watchdog.

- [ ] **Step 9: Review the production diff against privacy constraints**

Run:

```powershell
git diff --check
git diff -- app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt
```

Confirm the production diff contains no file I/O, `SharedPreferences`, Room writes outside the coordinator, logging, raw text fields, new package names, permission changes, or Activity-name allowlists.

- [ ] **Step 10: Commit the controller fix**

```powershell
git add -- app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt
git commit -m "fix: recognize opaque payment WebViews"
```

### Task 2: Full Verification and Safe Device Acceptance

**Files:**
- Modify: `docs/testing/manual-android-checklist.md`
- Modify ignored ledger: `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/progress.md`

**Interfaces:**
- Consumes: Task 1 APK behavior and the existing Settings screen transaction-count text.
- Produces: verified Debug APK plus a truthful Task 14 checklist entry; it does not change production code.

- [ ] **Step 1: Run a fresh full JVM suite**

Run with an independent 180-second watchdog:

```powershell
.\gradlew.bat --stop
.\gradlew.bat --no-daemon --max-workers=1 --console=plain clean testDebugUnitTest
```

Expected: exit 0 and XML totals with 0 failures, 0 errors, and 0 skipped tests. Parse `app/build/test-results/testDebugUnitTest/TEST-*.xml`; do not infer totals from console text alone.

- [ ] **Step 2: Run lint, AndroidTest compilation, and APK assembly serially**

Run each command separately with its own 180-second watchdog:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 --console=plain lintDebug
.\gradlew.bat --no-daemon --max-workers=1 --console=plain compileDebugAndroidTestKotlin
.\gradlew.bat --no-daemon --max-workers=1 --console=plain assembleDebug
```

Expected: all commands exit 0, lint reports 0 errors, and `app/build/outputs/apk/debug/app-debug.apk` exists. Do not run connected tests.

- [ ] **Step 3: Record APK identity and pre-install baseline**

Compute SHA-256 and size locally:

```powershell
Get-FileHash -Algorithm SHA256 app\build\outputs\apk\debug\app-debug.apk
Get-Item app\build\outputs\apk\debug\app-debug.apk | Select-Object Length
```

On device `81c74ce1`, launch `com.local.autobookkeeping/.LauncherActivity`, navigate to Settings, and read only `当前共有 N 笔交易` from an in-memory UI hierarchy. Expected pre-install baseline: 1 transaction. Do not print merchant, account, note, notification, OCR, or page raw text.

- [ ] **Step 4: Replacement-install without clearing data**

Run only:

```powershell
& 'E:\软件开发\DevTools\Android\sdk\platform-tools\adb.exe' -s 81c74ce1 install -r 'E:\软件开发\自动记账App\.worktrees\android-mvp\app\build\outputs\apk\debug\app-debug.apk'
```

Set the command timeout to 10 seconds. If it reaches 10 seconds, terminate that exact process once, check `pm path com.local.autobookkeeping`, and do not retry blindly. Never use uninstall or `pm clear`.

- [ ] **Step 5: Verify data and permissions survived replacement install**

Launch the app and re-read the transaction count; expected: still 1. Read only booleans from secure settings: notification listener contains `com.local.autobookkeeping`, accessibility services contains `com.local.autobookkeeping`, and global `accessibility_enabled` is `1`. If Android disables accessibility during replacement install, open the settings page and ask the user to re-enable it before continuing.

- [ ] **Step 6: Reuse the existing ¥1.00微信 detail page**

Open微信, enter “微信支付”, reopen the already completed ¥1.00 bill detail, and leave it visible at least five seconds. Do not make a new payment. Then open the bookkeeping app and verify one of these valid outcomes:

- High-confidence save: transaction count changes from 1 to 2 and the new row is one ¥1.00 expense.
- Pending confirmation: exactly one ¥1.00 expense appears as pending; choose the conservative `其他` category, confirm once, then count changes from 1 to 2.

Any zero record, income direction, wrong amount, multiple new rows, or raw-data display is a failure requiring renewed diagnosis.

- [ ] **Step 7: Verify repeated-page deduplication**

Reopen the same ¥1.00 detail page for five seconds, return to the ledger, and verify the transaction count remains 2 and statistics still include the ¥1.00 expense exactly once.

- [ ] **Step 8: Update the manual checklist and progress ledger**

Append exact evidence to `docs/testing/manual-android-checklist.md`: device/API, old and new APK SHA, pre/post-install count, service booleans, recognized outcome, final count, and duplicate-page result. State that no connected test, uninstall, clear-data command, new payment, screenshot persistence, or raw-text logging was used.

Append the same high-level state to the ignored progress file without raw merchant/account/page data. If any device step fails, mark it failed rather than changing it to pass.

- [ ] **Step 9: Verify docs and commit acceptance evidence**

```powershell
git diff --check
git diff -- docs/testing/manual-android-checklist.md
git add -- docs/testing/manual-android-checklist.md
git commit -m "test: verify opaque payment page recognition"
git status --short --branch
```

Expected: tracked worktree clean after the commit; ignored progress data remains untracked by design.

## Plan Self-Review

- Spec coverage: allowed packages, settings gate, empty-tree boundary, 2-second/64-entry throttle, OCR parser gate, failure/cancellation behavior, bitmap ownership, privacy, dedupe, full build, replacement install, restored baseline, existing-payment reuse, and repeat-page acceptance all map to explicit steps.
- Placeholder scan: complete; every behavior, command, expected result, error path, and commit boundary is explicit.
- Type consistency: the plan uses existing `PageSnapshot`, `RecognitionDebouncer`, `CaptureResult`, `LocalTextRecognizer`, `PaymentPageParser`, and `RecognitionCandidateProcessor` signatures; the only constructor addition is consistently named `opaquePageDebouncer`.
