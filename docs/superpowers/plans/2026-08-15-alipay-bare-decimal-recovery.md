# Alipay Bare Decimal Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover a single OCR amount whose人民币 symbol was omitted on a trusted Alipay bill-detail page, while leaving every other recognition path fail-closed.

**Architecture:** Add a pure `TrustedAlipayBareAmountRecovery` preprocessor beside the existing trusted accessibility-anchor merger. It may synthesize a currency prefix only when the original accessibility snapshot proves the official Alipay detail context and the OCR list contains exactly one strict bare two-decimal element and no already-recognizable currency amount; the existing `AlipayPageParser` remains the sole candidate validator.

**Tech Stack:** Kotlin, JUnit/Robolectric, Android accessibility screenshot capture, bundled ML Kit Chinese OCR, Gradle 9.5.0, ADB.

## Global Constraints

- Required package is exactly `com.eg.android.AlipayGphone`.
- Trusted accessibility anchors are only trimmed exact `账单详情` and `账单明细`.
- Bare amount syntax is exactly `[0-9]{1,8}[.,][0-9]{2}`; no sign, spaces, labels, units, integers, or other precision.
- Recovery is forbidden when zero or multiple bare candidates exist or OCR already contains a recognizable人民币 amount.
- Never copy accessibility amount, direction, merchant, account, time, or event signals.
- Never log, return, display, or persist OCR/page/notification raw text or bitmap data.
- Add no network, storage, overlay, receiver, provider, database, or schema changes.
- Preserve existing WeChat, notification, manual screenshot, Quick Settings, and MediaProjection behavior.
- Every direct ADB command uses a 10-second timeout.
- Do not run connected tests, uninstall, clear application data, restart ADB, or reboot/unplug the device.

Before running commands in this plan, define the exact cached tools once:

```powershell
$gradle='C:\Users\35020\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat'
$adb='E:\软件开发\DevTools\Android\sdk\platform-tools\adb.exe'
```

---

### Task 1: Pure trusted Alipay bare-amount recovery

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/TrustedAlipayBareAmountRecovery.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMerger.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/TrustedAlipayBareAmountRecoveryTest.kt`
- Test: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMergerTest.kt`

**Interfaces:**
- Consumes: `PageSnapshot original` and `List<ObservedTextElement> ocrElements`.
- Produces: `TrustedAlipayBareAmountRecovery.recover(original, ocrElements): List<ObservedTextElement>`.
- Shares: `TrustedAccessibilityAnchorMerger.hasTrustedContext(snapshot): Boolean` so package/anchor rules have one source of truth.

- [ ] **Step 1: Write the failing pure behavior tests**

Create `TrustedAlipayBareAmountRecoveryTest.kt` with real `PageSnapshot` and `ObservedTextElement` values. Include the following tests and helpers:

```kotlin
class TrustedAlipayBareAmountRecoveryTest {
    @Test
    fun `unique bare two-decimal amount is recovered in exact Alipay detail context`() {
        val bare = element("1.00", 80)
        val recovered = TrustedAlipayBareAmountRecovery.recover(
            snapshot(ALIPAY_PACKAGE, element("账单详情", 0)),
            listOf(element("中国电信", 40), bare),
        )

        assertEquals("¥1.00", recovered.last().text)
        assertEquals(bare.bounds, recovered.last().bounds)
        assertEquals("1.00", bare.text)
    }

    @Test
    fun `bill detail alias supports comma decimal normalization`() {
        val recovered = TrustedAlipayBareAmountRecovery.recover(
            snapshot(ALIPAY_PACKAGE, element("账单明细", 0)),
            listOf(element("12,34", 80)),
        )

        assertEquals("¥12.34", recovered.single().text)
    }

    @Test
    fun `wrong package missing anchor and containing anchor text stay unchanged`() {
        val bare = listOf(element("1.00", 80))
        val originals = listOf(
            snapshot("com.tencent.mm", element("账单详情", 0)),
            snapshot(ALIPAY_PACKAGE, element("支付宝", 0)),
            snapshot(ALIPAY_PACKAGE, element("支付宝账单详情服务", 0)),
        )

        originals.forEach { original ->
            assertSame(bare, TrustedAlipayBareAmountRecovery.recover(original, bare))
        }
    }

    @Test
    fun `existing currency amount or non-unique bare decimals stay unchanged`() {
        val cases = listOf(
            listOf(element("¥1.00", 40), element("2.00", 80)),
            listOf(element("CNY 1.00", 40), element("2.00", 80)),
            listOf(element("1.00", 40), element("2.00", 80)),
            emptyList(),
        )
        val original = snapshot(ALIPAY_PACKAGE, element("账单详情", 0))

        cases.forEach { input ->
            assertSame(input, TrustedAlipayBareAmountRecovery.recover(original, input))
        }
    }

    @Test
    fun `unsafe bare number forms stay unchanged`() {
        val original = snapshot(ALIPAY_PACKAGE, element("账单详情", 0))
        val unsafe = listOf("1", "1.0", "1.000", "+1.00", "-1.00", "1.00 元", "实付1.00", "123456789.00")

        unsafe.forEach { text ->
            val input = listOf(element(text, 80))
            assertSame(input, TrustedAlipayBareAmountRecovery.recover(original, input))
        }
    }
}
```

Use the same fixed `Instant`, `TextBounds`, and package helper style as `TrustedAccessibilityAnchorMergerTest`. Import `assertEquals`, `assertSame`, and `Test` explicitly.

Add these exact helpers inside the test class:

```kotlin
private fun snapshot(
    packageName: String,
    vararg elements: ObservedTextElement,
) = PageSnapshot(
    packageName = packageName,
    elements = elements.toList(),
    observedAt = NOW,
)

private fun element(text: String, top: Int) = ObservedTextElement(
    text = text,
    bounds = TextBounds(0, top, 200, top + 30),
)

private companion object {
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    val NOW: Instant = Instant.parse("2026-08-15T03:00:00Z")
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `R:\` with the configured JDK, SDK, and Gradle cache:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='E:\软件开发\DevTools\Android\sdk'
$env:ANDROID_SDK_ROOT='E:\软件开发\DevTools\Android\sdk'
$env:GRADLE_USER_HOME='C:\Users\35020\.gradle'
& 'C:\Users\35020\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' :app:testDebugUnitTest --tests 'com.local.autobookkeeping.recognition.accessibility.TrustedAlipayBareAmountRecoveryTest' --no-daemon --max-workers=1 --console=plain --offline
```

Expected: compilation fails only because `TrustedAlipayBareAmountRecovery` does not exist.

- [ ] **Step 3: Centralize the trusted context predicate**

Add this method to `TrustedAccessibilityAnchorMerger` and use it in `merge` instead of duplicating the package and anchor checks:

```kotlin
internal fun hasTrustedContext(snapshot: PageSnapshot): Boolean =
    snapshot.packageName == ALIPAY_PACKAGE &&
        snapshot.elements.any { it.text.trim() in TRUSTED_DETAIL_ANCHORS }
```

Keep `ALIPAY_PACKAGE` and `TRUSTED_DETAIL_ANCHORS` private inside that object. Existing merger behavior and ordering must not change.

- [ ] **Step 4: Implement the minimal pure recovery**

Create `TrustedAlipayBareAmountRecovery.kt`:

```kotlin
package com.local.autobookkeeping.recognition.accessibility

import com.local.autobookkeeping.recognition.ObservedTextElement
import com.local.autobookkeeping.recognition.PageSnapshot

internal object TrustedAlipayBareAmountRecovery {
    fun recover(
        original: PageSnapshot,
        ocrElements: List<ObservedTextElement>,
    ): List<ObservedTextElement> {
        if (!TrustedAccessibilityAnchorMerger.hasTrustedContext(original)) return ocrElements
        if (ocrElements.any { CURRENCY_AMOUNT.containsMatchIn(it.text) }) return ocrElements

        val candidates = ocrElements.mapIndexedNotNull { index, element ->
            index.takeIf { BARE_TWO_DECIMAL.matches(element.text.trim()) }
        }
        if (candidates.size != 1) return ocrElements

        val selectedIndex = candidates.single()
        return ocrElements.mapIndexed { index, element ->
            if (index != selectedIndex) {
                element
            } else {
                element.copy(text = "¥" + element.text.trim().replace(',', '.'))
            }
        }
    }

    private val BARE_TWO_DECIMAL = Regex("[0-9]{1,8}[.,][0-9]{2}")
    private val CURRENCY_AMOUNT = Regex(
        "(?:人民币|CNY|RMB|[¥￥])\\s*[0-9]{1,8}(?:[.,][0-9]{1,2})?",
        RegexOption.IGNORE_CASE,
    )
}
```

- [ ] **Step 5: Run pure tests and verify GREEN**

Run both pure classes:

```powershell
& $gradle :app:testDebugUnitTest --tests 'com.local.autobookkeeping.recognition.accessibility.TrustedAlipayBareAmountRecoveryTest' --tests 'com.local.autobookkeeping.recognition.accessibility.TrustedAccessibilityAnchorMergerTest' --no-daemon --max-workers=1 --console=plain --offline
```

Expected: both suites pass with zero failures and errors.

- [ ] **Step 6: Commit the pure component**

```powershell
git add -- app/src/main/java/com/local/autobookkeeping/recognition/accessibility/TrustedAlipayBareAmountRecovery.kt app/src/main/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMerger.kt app/src/test/java/com/local/autobookkeeping/recognition/accessibility/TrustedAlipayBareAmountRecoveryTest.kt
git diff --cached --check
git commit -m "feat: recover trusted Alipay bare amounts"
```

---

### Task 2: Auto-page controller integration

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt:278-291`
- Modify: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt:76-100`

**Interfaces:**
- Consumes: `TrustedAlipayBareAmountRecovery.recover(original, ocrElements)` from Task 1.
- Produces: the existing controller sends a normal `RecognitionCandidate` to its existing `RecognitionCandidateProcessor`; no public API changes.

- [ ] **Step 1: Write the controller regression test**

Add this separate test after the existing currency-prefixed Alipay anchor test:

```kotlin
@Test
fun `trusted Alipay detail recovers one OCR bare two-decimal amount`() = runTest {
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    gateway.attach { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) }
    val bareAmountRecognizer = LocalTextRecognizer {
        listOf(
            element("中国电信", 40),
            element("1.00", 80),
        )
    }

    controller(bareAmountRecognizer).onWindowChanged(
        page(ALIPAY_PACKAGE, "账单详情"),
    )

    val candidate = processor.candidates.single()
    assertEquals(PaymentPlatform.ALIPAY, candidate.platform)
    assertEquals(Direction.EXPENSE, candidate.direction)
    assertEquals(100L, candidate.amountMinor)
    assertTrue(bitmap.isRecycled)
}
```

- [ ] **Step 2: Run the exact test and verify RED**

```powershell
& $gradle :app:testDebugUnitTest --tests 'com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionControllerTest.trusted Alipay detail recovers one OCR bare two-decimal amount' --no-daemon --max-workers=1 --console=plain --offline
```

Expected: the test executes but `processor.candidates.single()` fails because the controller has not called the recovery component.

- [ ] **Step 3: Wire recovery before anchor merge**

In `processCapturedBitmap`, replace the single anchor-merge call with:

```kotlin
val recoveredElements = TrustedAlipayBareAmountRecovery.recover(original, ocrElements)
val elements = TrustedAccessibilityAnchorMerger.merge(original, recoveredElements)
```

Do not change capture ownership, cancellation propagation, parser selection, debounce, diagnostics, coordinator routing, or bitmap recycling.

- [ ] **Step 4: Run focused integration and parser regressions**

```powershell
& $gradle :app:testDebugUnitTest --tests 'com.local.autobookkeeping.recognition.accessibility.*' --tests 'com.local.autobookkeeping.recognition.tile.ScreenshotRecognitionControllerTest' --tests 'com.local.autobookkeeping.recognition.PaymentPageParserTest' --no-daemon --max-workers=1 --console=plain --offline
```

Expected: all selected tests pass with zero failures and errors.

- [ ] **Step 5: Commit controller integration**

```powershell
git add -- app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt
git diff --cached --check
git commit -m "fix: recognize Alipay OCR amounts without currency glyphs"
```

---

### Task 3: Fresh verification, privacy audit, and clean APK

**Files:**
- Verify only; no expected production edits.

**Interfaces:**
- Consumes: committed Task 1 and Task 2 implementation.
- Produces: a verified `app/build/outputs/apk/debug/app-debug.apk` suitable for replacement install.

- [ ] **Step 1: Run fresh full JVM verification**

```powershell
& $gradle clean testDebugUnitTest --no-daemon --max-workers=1 --console=plain --offline
```

Use one 180-second watchdog. Read all `TEST-*.xml` files and report suites, tests, failures, errors, and skipped counts. Do not claim success from exit code alone.

- [ ] **Step 2: Run Lint**

```powershell
& $gradle lintDebug --no-daemon --max-workers=1 --console=plain --offline
```

Use one 180-second watchdog. Parse `app/build/reports/lint-results-debug.xml` and require zero errors; report warning count separately.

- [ ] **Step 3: Compile Android tests and assemble**

```powershell
& $gradle compileDebugAndroidTestKotlin assembleDebug --no-daemon --max-workers=1 --console=plain --offline
```

Use one 180-second watchdog. Require exit code 0 and an existing APK; do not run connected tests.

- [ ] **Step 4: Audit the final tree and artifact**

Verify all of the following:

```powershell
git diff --check
git status --short --branch
Get-ChildItem app/src -Recurse -File | Select-String -Pattern 'ABK_ALIPAY_PROBE|AlipayDebugProbeReceiver|Log\.|pageRawText|notificationBody|rawText'
Test-Path local.properties
Get-FileHash app/build/outputs/apk/debug/app-debug.apk -Algorithm SHA256
```

Expected: no diagnostic/raw-text/log matches in the changed production files, no `local.properties`, a clean tracked tree, and a SHA-256 hash for the APK. Recheck the merged manifest contains no network, storage, overlay, or temporary diagnostic component changes.

---

### Task 4: Existing Alipay detail device acceptance and dedupe

**Files:**
- Modify: `docs/testing/manual-android-checklist.md`
- Modify ignored evidence ledger: `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/progress.md`

**Interfaces:**
- Consumes: the clean verified Debug APK and already connected device serial `81c74ce1`.
- Produces: a manual acceptance record without raw page/OCR/notification data.

- [ ] **Step 1: Replacement install without data deletion**

Run with a strict 10-second tool timeout:

```powershell
& 'E:\软件开发\DevTools\Android\sdk\platform-tools\adb.exe' -s 81c74ce1 install -r 'R:\app\build\outputs\apk\debug\app-debug.apk'
```

Expected: `Success`. Do not use uninstall or `pm clear`.

- [ ] **Step 2: Verify service preconditions**

Read only:

```powershell
& $adb -s 81c74ce1 shell "settings get secure accessibility_enabled; settings get secure enabled_accessibility_services; settings get secure enabled_notification_listeners; dumpsys accessibility | grep -E 'Bound services|PaymentPageAccessibilityService'"
```

Require the exact app notification listener and accessibility service to be enabled and the accessibility service to be bound. If replacement install leaves it unbound, launch the app once; if still unbound, ask the user to toggle the app service through Android Settings instead of writing secure settings from ADB.

- [ ] **Step 3: Open the existing Alipay detail exactly once**

```powershell
& $adb -s 81c74ce1 shell "monkey -p com.eg.android.AlipayGphone -c android.intent.category.LAUNCHER 1"
& $adb -s 81c74ce1 shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
```

Require foreground `com.eg.android.AlipayGphone/com.alipay.mobile.nebulax.xriver.activity.XRiverActivity`. Wait at least five seconds without creating a new payment.

- [ ] **Step 4: Verify recognition through the app UI**

Open the bookkeeping app and inspect only its rendered UI:

- If a new pending支付宝 expense exists, verify its amount matches the visible source bill, select category `其他`, and save once.
- If no new pending item exists, verify whether the same transaction was already present; do not infer success from database timestamps alone.
- Do not use UI hierarchy dumps, screenshots, database copies, raw logs, or temporary debug receivers.

If neither a pending record nor a provable duplicate is visible, stop and report the exact boundary; do not add another speculative fix.

- [ ] **Step 5: Reopen the same detail and verify dedupe**

Return to the same existing Alipay detail, wait at least five seconds, then reopen the bookkeeping app. Confirm the transaction count and pending count do not increase.

- [ ] **Step 6: Record sanitized evidence and commit tracked acceptance docs**

Update the checklist and ignored progress ledger with only service state, normalized outcome, record-count effect, build/test counts, APK hash, and the no-raw-data statement. Then commit only the tracked checklist if it changed:

```powershell
git add -- docs/testing/manual-android-checklist.md
git diff --cached --check
git commit -m "test: verify Alipay bare amount recognition"
git status --short --branch
```
