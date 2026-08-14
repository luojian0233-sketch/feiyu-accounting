# Alipay Debug Probe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add, run, and then remove a Debug-only probe that identifies whether Alipay bookkeeping fails at accessibility screenshot capture or normalized OCR/parser processing.

**Architecture:** A pure `AlipayDebugProbe` orchestrator maps the existing capture and screenshot-recognition APIs to a small, redacted result vocabulary. A Debug-only ordered broadcast receiver exposes that orchestrator to ADB shell behind `android.permission.DUMP`; no production source or final manifest remains changed after diagnosis.

**Tech Stack:** Kotlin, Android BroadcastReceiver, coroutines, existing `AccessibilityCaptureGateway`, existing `ScreenshotRecognitionController`, JUnit/Robolectric, Gradle, ADB.

## Global Constraints

- The probe exists only under `app/src/debug` and is removed after diagnosis.
- The receiver requires `android.permission.DUMP` and is invoked by explicit component only.
- Never return, log, persist, or display amount, merchant, account, notification body, OCR text, or bitmap data.
- Recycle every successful capture exactly once; the existing screenshot controller retains ownership in `process` mode.
- Use a 5-second internal timeout and normalize exceptions to `PROBE_INTERNAL_ERROR`; rethrow coroutine cancellation inside the orchestrator.
- Do not run connected tests, uninstall, or clear application data.
- Every direct ADB command uses a 10-second timeout.

---

### Task 1: Debug-only redacted probe

**Files:**
- Create: `app/src/debug/java/com/local/autobookkeeping/debug/AlipayDebugProbe.kt`
- Create: `app/src/debug/java/com/local/autobookkeeping/debug/AlipayDebugProbeReceiver.kt`
- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/test/java/com/local/autobookkeeping/debug/AlipayDebugProbeTest.kt`

**Interfaces:**
- Consumes: `suspend () -> CaptureResult` and `suspend (Bitmap, String?, Instant) -> RecognitionOutcome`.
- Produces: `suspend fun run(mode: String): String` with only the result vocabulary defined below.

- [ ] **Step 1: Write the failing unit tests**

Create tests that instantiate the wished-for `AlipayDebugProbe` and assert these real ownership/result behaviors:

```kotlin
@Test fun `capture mode reports success and recycles bitmap`() = runTest {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val probe = AlipayDebugProbe(
        capture = { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) },
        process = { _, _, _ -> error("must not process") },
    )
    assertEquals("CAPTURE_SUCCESS", probe.run("capture"))
    assertTrue(bitmap.isRecycled)
}

@Test fun `capture failure exposes only normalized reason`() = runTest {
    val probe = probe(capture = CaptureResult.Failure(CaptureFailure.SECURE_WINDOW))
    assertEquals("CAPTURE_FAILURE_SECURE_WINDOW", probe.run("capture"))
}

@Test fun `process mode passes successful capture to processor and returns rejected reason`() = runTest {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val probe = AlipayDebugProbe(
        capture = { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) },
        process = { received, packageName, _ ->
            assertSame(bitmap, received)
            assertEquals(ALIPAY_PACKAGE, packageName)
            received.recycle()
            RecognitionOutcome.Rejected(RecognitionRejectReason.INCOMPLETE_FIELDS)
        },
    )
    assertEquals("REJECTED_INCOMPLETE_FIELDS", probe.run("process"))
}

@Test fun `process outcomes use stable redacted vocabulary`() = runTest {
    suspend fun resultFor(outcome: RecognitionOutcome): String {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return AlipayDebugProbe(
            capture = { CaptureResult.Success(bitmap, ALIPAY_PACKAGE) },
            process = { received, _, _ ->
                received.recycle()
                outcome
            },
        ).run("process")
    }
    assertEquals("SAVED_PENDING", resultFor(RecognitionOutcome.Saved(42L, pending = true)))
    assertEquals("SAVED_CONFIRMED", resultFor(RecognitionOutcome.Saved(42L, pending = false)))
    assertEquals("DUPLICATE", resultFor(RecognitionOutcome.Duplicate))
}

@Test fun `debug manifest restricts the explicit receiver to dump permission`() {
    val manifest = File("src/debug/AndroidManifest.xml").readText(Charsets.UTF_8)
    assertTrue(manifest.contains(".debug.AlipayDebugProbeReceiver"))
    assertTrue(manifest.contains("android:permission=\"android.permission.DUMP\""))
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.debug.AlipayDebugProbeTest" --no-daemon --max-workers=1 --console=plain
```

Expected: compilation fails because `AlipayDebugProbe` does not exist.

- [ ] **Step 3: Implement the minimal pure probe**

Implement `AlipayDebugProbe.run(mode)` with `withTimeout(5_000)` and exactly these mappings:

```text
capture success                 -> CAPTURE_SUCCESS
capture failure X               -> CAPTURE_FAILURE_X
system consent required         -> REQUIRES_CONSENT
Saved(pending=true)             -> SAVED_PENDING
Saved(pending=false)            -> SAVED_CONFIRMED
Duplicate                       -> DUPLICATE
NeedsSelection                  -> NEEDS_SELECTION
Rejected(reason=X)              -> REJECTED_X
unknown mode or other exception -> PROBE_INTERNAL_ERROR
```

In `capture` mode, recycle a successful bitmap in `finally`. In `process` mode, transfer bitmap ownership to the existing processor and do not recycle it again in the probe.

- [ ] **Step 4: Implement the Debug receiver and manifest restriction**

The receiver reads `mode` from an Intent extra, constructs the probe from `applicationGraph.accessibilityCaptureGateway::capture` and `applicationGraph.screenshotRecognitionController::process`, calls `goAsync()`, and returns the result through `PendingResult.setResultData()` before `finish()`.

Declare only in `app/src/debug/AndroidManifest.xml`:

```xml
<receiver
    android:name=".debug.AlipayDebugProbeReceiver"
    android:exported="true"
    android:permission="android.permission.DUMP" />
```

- [ ] **Step 5: Run focused GREEN and assemble the diagnostic APK**

Run the focused test command from Step 2, then:

```powershell
gradlew.bat :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

Expected: focused tests pass and `app/build/outputs/apk/debug/app-debug.apk` is produced.

### Task 2: Device boundary diagnosis and cleanup

**Files:**
- Delete after diagnosis: `app/src/debug/java/com/local/autobookkeeping/debug/AlipayDebugProbe.kt`
- Delete after diagnosis: `app/src/debug/java/com/local/autobookkeeping/debug/AlipayDebugProbeReceiver.kt`
- Delete after diagnosis: `app/src/debug/AndroidManifest.xml`
- Delete after diagnosis: `app/src/test/java/com/local/autobookkeeping/debug/AlipayDebugProbeTest.kt`
- Modify: `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/progress.md` (ignored evidence ledger only)

**Interfaces:**
- Consumes: explicit ordered broadcasts to `com.local.autobookkeeping/.debug.AlipayDebugProbeReceiver` with extra `mode`.
- Produces: one normalized capture result and, only after capture succeeds, one normalized process result.

- [ ] **Step 1: Install without removing data and verify preconditions**

Run `adb install -r`, then verify the package is installed, transaction count remains 2, accessibility service is enabled, and Alipay source/master switches remain enabled. Do not continue if any preservation check fails.

- [ ] **Step 2: Capture-only probe on the existing Alipay detail**

Open the existing Alipay detail and run one ordered broadcast with `mode=capture`. Record only the normalized result. If it is not `CAPTURE_SUCCESS`, stop; that failure enum is the located boundary.

- [ ] **Step 3: One process probe after successful capture**

Run one ordered broadcast with `mode=process`. Record only its normalized result, then read transaction count and pending state from the app UI. Do not repeat the process probe.

- [ ] **Step 4: Remove the temporary probe and restore the final APK**

Delete all four temporary files with `apply_patch`, run the focused baseline accessibility/capture tests, assemble Debug again, and use `adb install -r` to restore an APK without the exported diagnostic receiver. Verify `cmd package query-receivers` no longer resolves `AlipayDebugProbeReceiver`.

- [ ] **Step 5: Record the root-cause boundary**

Append the normalized probe results, transaction-count effect, cleanup verification, and the no-raw-data statement to the ignored progress ledger. Do not commit temporary probe code. Start a separate TDD fix only after the boundary and reason are known.
