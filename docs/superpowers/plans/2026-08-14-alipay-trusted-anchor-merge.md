# Alipay Trusted Anchor Merge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow an exact Alipay detail-page anchor from the accessibility tree to anchor screenshot OCR fields without relaxing parser signatures or copying business data from accessibility.

**Architecture:** A small pure merger selects only exact `账单详情`/`账单明细` elements from an Alipay accessibility snapshot and appends missing anchors to OCR elements using a normalized-text-plus-bounds key. `AutoPageRecognitionController` invokes it only inside the existing signed incomplete-page screenshot fallback; the unchanged `AlipayPageParser` remains the sole candidate validator.

**Tech Stack:** Kotlin, coroutines, existing Android accessibility screenshot/OCR pipeline, JUnit/Robolectric, Gradle, ADB.

## Global Constraints

- Only package `com.eg.android.AlipayGphone` may contribute trusted accessibility anchors.
- Only exact trimmed text `账单详情` or `账单明细` is trusted; standalone `支付宝`, substring, regex, and fuzzy matches are forbidden.
- Never merge accessibility-derived amount, direction, merchant, account, time, event signal, or any other text.
- Deduplicate by trimmed text plus complete `TextBounds`.
- Keep WeChat empty-WebView behavior, parser validation, cancellation, capture package guard, debounce, bitmap ownership, privacy, and permissions unchanged.
- Do not persist or output raw page/OCR text or screenshots.
- Device validation uses only an existing Alipay detail, `adb install -r`, and direct ADB commands with 10-second timeouts; do not run connected tests, uninstall, or clear data.

---

### Task 1: Pure trusted-anchor merger

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMerger.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMergerTest.kt`

**Interfaces:**
- Consumes: `PageSnapshot original` and `List<ObservedTextElement> ocrElements`.
- Produces: `internal fun merge(original: PageSnapshot, ocrElements: List<ObservedTextElement>): List<ObservedTextElement>`.

- [ ] **Step 1: Write focused failing tests**

Use literal elements and bounds. The first test must prove only exact detail anchors cross the boundary:

```kotlin
@Test
fun `Alipay merge copies only exact detail anchors`() {
    val detail = element("账单详情", top = 0)
    val ocrAmount = element("¥1.00", top = 80)
    val original = snapshot(
        ALIPAY_PACKAGE,
        detail,
        element("支付宝", 20),
        element("支付成功", 40),
        element("¥999.00", 60),
        element("商户：不可信字段", 100),
    )

    val merged = TrustedAccessibilityAnchorMerger.merge(original, listOf(ocrAmount))

    assertEquals(listOf(ocrAmount, detail), merged)
}
```

Add these independent behavior tests:

```kotlin
@Test fun `wrong package never contributes an anchor`() {
    val amount = element("¥1.00", 80)
    val original = snapshot("com.tencent.mm", element("账单详情", 0))
    assertEquals(listOf(amount), TrustedAccessibilityAnchorMerger.merge(original, listOf(amount)))
}

@Test fun `standalone Alipay title and containing text are not trusted anchors`() {
    val amount = element("¥1.00", 80)
    val original = snapshot(
        ALIPAY_PACKAGE,
        element("支付宝", 0),
        element("支付宝账单详情服务", 40),
    )
    assertEquals(listOf(amount), TrustedAccessibilityAnchorMerger.merge(original, listOf(amount)))
}

@Test fun `trimmed text and identical bounds deduplicate an OCR anchor`() {
    val ocrAnchor = element("账单详情", 0)
    val original = snapshot(ALIPAY_PACKAGE, element(" 账单详情 ", 0))
    assertEquals(listOf(ocrAnchor), TrustedAccessibilityAnchorMerger.merge(original, listOf(ocrAnchor)))
}

@Test fun `same trusted text at different bounds remains distinct`() {
    val ocrAnchor = element("账单详情", 40)
    val trustedAnchor = element("账单详情", 0)
    val original = snapshot(ALIPAY_PACKAGE, trustedAnchor)
    assertEquals(
        listOf(ocrAnchor, trustedAnchor),
        TrustedAccessibilityAnchorMerger.merge(original, listOf(ocrAnchor)),
    )
}

private fun snapshot(packageName: String, vararg elements: ObservedTextElement) = PageSnapshot(
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
    val NOW: Instant = Instant.parse("2026-08-14T07:00:00Z")
}
```

The mutation each test catches is respectively: missing field filter, missing package gate, loose signature match, wrong duplicate key, and text-only deduplication.

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
gradle.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.recognition.accessibility.TrustedAccessibilityAnchorMergerTest" --no-daemon --max-workers=1 --console=plain --offline
```

Expected: `compileDebugUnitTestKotlin` fails because `TrustedAccessibilityAnchorMerger` does not exist.

- [ ] **Step 3: Implement the minimal merger**

Create a single-purpose internal object:

```kotlin
internal object TrustedAccessibilityAnchorMerger {
    fun merge(
        original: PageSnapshot,
        ocrElements: List<ObservedTextElement>,
    ): List<ObservedTextElement> {
        if (original.packageName != ALIPAY_PACKAGE) return ocrElements
        val trusted = original.elements.filter { it.text.trim() in TRUSTED_DETAIL_ANCHORS }
        if (trusted.isEmpty()) return ocrElements
        val seen = ocrElements.mapTo(mutableSetOf()) { it.anchorKey() }
        return buildList {
            addAll(ocrElements)
            trusted.forEach { anchor -> if (seen.add(anchor.anchorKey())) add(anchor) }
        }
    }

    private fun ObservedTextElement.anchorKey() = AnchorKey(text.trim(), bounds)
    private data class AnchorKey(val text: String, val bounds: TextBounds)
    private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    private val TRUSTED_DETAIL_ANCHORS = setOf("账单详情", "账单明细")
}
```

- [ ] **Step 4: Run focused GREEN**

Run the command from Step 2. Expected: all merger tests pass with zero failures/errors.

### Task 2: Incomplete Alipay OCR fallback integration

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt`

**Interfaces:**
- Consumes: `TrustedAccessibilityAnchorMerger.merge(original, elements)` after successful OCR.
- Produces: the existing `RecognitionCandidateProcessor.process(candidate)` call; no new public API.

- [ ] **Step 1: Write the failing controller regression**

Change the test-only `RecordingProcessor` to retain real candidates:

```kotlin
private class RecordingProcessor : RecognitionCandidateProcessor {
    val candidates = mutableListOf<RecognitionCandidate>()
    val calls: Int get() = candidates.size
    override suspend fun process(candidate: RecognitionCandidate): RecognitionOutcome {
        candidates += candidate
        return RecognitionOutcome.Duplicate
    }
}
```

Add the real failure shape—OCR has an amount but no event/detail anchor:

```kotlin
@Test
fun `exact Alipay detail accessibility anchor can anchor OCR-only amount`() = runTest {
    val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    gateway.attach { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) }
    val amountOnlyRecognizer = LocalTextRecognizer { listOf(element("¥1.00", 80)) }

    controller(amountOnlyRecognizer).onWindowChanged(
        page(ALIPAY_PACKAGE, "账单详情"),
    )

    val candidate = processor.candidates.single()
    assertEquals(PaymentPlatform.ALIPAY, candidate.platform)
    assertEquals(Direction.EXPENSE, candidate.direction)
    assertEquals(100L, candidate.amountMinor)
    assertTrue(bitmap.isRecycled)
}
```

- [ ] **Step 2: Run the controller test and verify behavior RED**

Run:

```powershell
gradle.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionControllerTest.exact Alipay detail accessibility anchor can anchor OCR-only amount" --no-daemon --max-workers=1 --console=plain --offline
```

Expected: the test executes and fails because `processor.candidates` is empty; the OCR-only snapshot is currently `NOT_PAYMENT_PAGE`.

- [ ] **Step 3: Wire the merger into the existing OCR boundary**

In `processCapturedBitmap`, keep OCR exception/cancellation behavior unchanged and replace only the snapshot element assignment:

```kotlin
val ocrElements = textRecognizer.recognize(bitmap)
val elements = TrustedAccessibilityAnchorMerger.merge(original, ocrElements)
val ocrSnapshot = PageSnapshot(
    packageName = original.packageName,
    elements = elements,
    observedAt = original.observedAt,
)
```

- [ ] **Step 4: Run focused GREEN and relevant regressions**

Run:

```powershell
gradle.bat :app:testDebugUnitTest `
  --tests "com.local.autobookkeeping.recognition.accessibility.*" `
  --tests "com.local.autobookkeeping.recognition.capture.ScreenshotRecognitionControllerTest" `
  --tests "com.local.autobookkeeping.recognition.PaymentPageParserTest" `
  --no-daemon --max-workers=1 --console=plain --offline
```

Expected: new regression and existing accessibility/capture/Alipay parser tests pass.

- [ ] **Step 5: Commit the production fix**

```powershell
git add app/src/main/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMerger.kt `
        app/src/main/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionController.kt `
        app/src/test/java/com/local/autobookkeeping/recognition/accessibility/TrustedAccessibilityAnchorMergerTest.kt `
        app/src/test/java/com/local/autobookkeeping/recognition/accessibility/AutoPageRecognitionControllerTest.kt
git commit -m "fix: merge trusted Alipay detail anchors"
```

### Task 3: Verification and real-device acceptance

**Files:**
- Modify: `docs/testing/manual-android-checklist.md`
- Modify: `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/progress.md` (ignored evidence ledger)

**Interfaces:**
- Consumes: final Debug APK and existing Alipay detail page.
- Produces: fresh automated evidence, one device pending record, confirmation result, and same-page dedupe result.

- [ ] **Step 1: Run fresh project verification**

Run serially with an independent 180-second watchdog per command:

```powershell
gradle.bat clean testDebugUnitTest --no-daemon --max-workers=1 --console=plain --offline
gradle.bat lintDebug --no-daemon --max-workers=1 --console=plain --offline
gradle.bat compileDebugAndroidTestKotlin assembleDebug --no-daemon --max-workers=1 --console=plain --offline
```

Record exact suite/test/failure counts, Lint error count, APK size, and SHA-256. Do not run connected tests.

- [ ] **Step 2: Perform privacy and scope audit**

Verify `git diff --check`, strict UTF-8 for changed text files, no new permissions, no network/file/log calls, no debug receiver, no raw text persistence, and no changes outside the four production/test files plus acceptance documentation.

- [ ] **Step 3: Install without data loss and verify prerequisites**

Use `adb install -r`; confirm install success, transaction count remains 2, accessibility and notification listener remain enabled, and master/Alipay switches are true. Stop immediately if preservation fails.

- [ ] **Step 4: Reopen the existing Alipay detail and verify one pending record**

Confirm the foreground component is the Alipay `XRiverActivity`, wait at least five seconds, then read the app UI. Expected: transaction count changes from 2 to 3 exactly once and the new record is an Alipay expense pending confirmation.

- [ ] **Step 5: Confirm and verify same-page dedupe**

Choose category `其他`, confirm and save, verify the pending marker clears, reopen the same Alipay detail, wait at least five seconds, and verify the transaction count remains 3.

- [ ] **Step 6: Record and commit acceptance evidence**

Append only normalized evidence to the checklist/progress ledger: build hashes/counts, install result, component, count transition, pending confirmation, dedupe count, and privacy constraints. Commit tracked acceptance documentation with:

```powershell
git add docs/testing/manual-android-checklist.md
git commit -m "test: verify Alipay detail recognition"
```
