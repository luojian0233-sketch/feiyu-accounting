# Task 11 Review Fix Round 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make automatic-bookkeeping settings launches crash-safe, make high-confidence auto-save fail closed, and normalize exact accessibility service component identities.

**Architecture:** Keep the Android launcher and accessibility parsing at the existing settings boundary, but make every platform failure return a stable Boolean. Adapt the persisted auto-save `Flow<Boolean>` into an `AutoSavePolicy` that preserves normal defaults while treating read failures as disabled and propagating coroutine cancellation.

**Tech Stack:** Kotlin, Android framework intents/components, DataStore Preferences, Kotlin coroutines Flow, JUnit 4/5, Robolectric.

## Global Constraints

- Scope is Task 11 review findings only; do not begin Task 12.
- Tests must exercise behavior rather than grep source.
- Preserve exact accessibility component identity, including package and class.
- Do not run connected tests, adb, or create/modify `local.properties`.
- Run at most one focused Gradle command with a 30-second timeout; stop on the known Java/ZipFS managed-sandbox failure.

---

### Task 1: Crash-safe notification settings launch

**Files:**
- Modify: `app/src/test/java/com/local/autobookkeeping/NotificationSettingsLauncherTest.kt`
- Modify: `app/src/test/java/com/local/autobookkeeping/AutomaticBookkeepingAccessTest.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/NotificationSettingsLauncher.kt`

**Interfaces:**
- Consumes: `NotificationSettingsLauncher.canOpen(action)` and `open(action)`.
- Produces: `openNotificationAccessSafely(launcher): Boolean`; application-context launches carry `Intent.FLAG_ACTIVITY_NEW_TASK`.

- [ ] **Step 1: Write failing behavior tests**

  Add tests where `canOpen` and `open` throw ordinary `RuntimeException`; assert fallback success when general settings remains available and stable `false` when all attempts fail. With a Robolectric application context, launch through `AndroidNotificationSettingsLauncher` and assert the started intent contains `FLAG_ACTIVITY_NEW_TASK`.

- [ ] **Step 2: Run the single focused Gradle command**

  Run the Task 1-3 focused JVM test classes together with a 30-second host timeout. Expected before implementation: the new API/behavior tests fail; if Gradle cannot start because `java.exe`/ZipFS is unavailable, record that environment evidence and do not retry.

- [ ] **Step 3: Implement the minimal launch fix**

  Build the Android intent as `Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`. Treat `RuntimeException` from both `canOpen` and `open` as an unsafe attempt, continue to the general-settings fallback, and return `false` after all attempts fail.

### Task 2: Fail-closed high-confidence auto-save

**Files:**
- Modify: `app/src/test/java/com/local/autobookkeeping/data/SettingsRepositoryTest.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/recognition/AutoSavePolicyTest.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/data/SettingsRepository.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/recognition/RecognitionCoordinator.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/AutoBookkeepingApplication.kt`

**Interfaces:**
- Consumes: `Flow<Boolean>` from `SettingsRepository.autoSaveHighConfidence`.
- Produces: `AutoSavePolicy.failClosed(enabled: Flow<Boolean>): AutoSavePolicy`.

- [ ] **Step 1: Write failing repository and policy tests**

  Assert that a repository backed by a `DataStore` whose data flow throws `IOException` emits `false`, while the existing fresh-store absent-key test remains `true` and the persisted user `false` remains `false`. Assert the policy returns normal flow values, maps both `IOException` and ordinary `RuntimeException` to `false`, and rethrows `CancellationException`.

- [ ] **Step 2: Implement the minimal flow boundaries**

  For the high-confidence setting only, map an absent key to the product default `true`, catch `IOException` after mapping and emit `false`, and rethrow other failures. Implement `AutoSavePolicy.failClosed` with `first()`, a dedicated cancellation rethrow, and `false` for other `Exception` values. Wire `ApplicationGraph` to this factory.

- [ ] **Step 3: Recheck coordinator behavior**

  Keep the existing `RecognitionCoordinatorTest` assertion that a high-confidence candidate creates a transaction with `pendingConfirmation = true`, category `待确认`, and `RecognitionDiagnostic.NEEDS_CONFIRMATION` whenever its policy returns `false`.

### Task 3: Accessibility component normalization

**Files:**
- Modify: `app/src/test/java/com/local/autobookkeeping/AutomaticBookkeepingAccessTest.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/AutomaticBookkeepingAccess.kt`

**Interfaces:**
- Consumes: enabled `AccessibilityServiceInfo` records supplied by `AccessibilityManager`.
- Produces: exact normalized `ComponentName` matches using `ComponentName.createRelative(packageName, name)`.

- [ ] **Step 1: Write failing mapping and failure-boundary tests**

  Build real `AccessibilityServiceInfo`/`ResolveInfo`/`ServiceInfo` records for a fully qualified class, a `.relative` class, a null `resolveInfo`, a same-package wrong service, and a similar package. Assert only the exact payment service matches. Assert `SecurityException` and ordinary `RuntimeException` from the enabled-service provider return `false`.

- [ ] **Step 2: Implement the minimal normalized comparison**

  Move the exception boundary around the provider call and service-info mapping, use `ComponentName.createRelative(info.packageName, info.name)`, ignore records without resolvable service metadata, and compare full `ComponentName` equality.

### Task 4: Static verification, report, and commit

**Files:**
- Modify: `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/task-11-implementer-report.md`

**Interfaces:**
- Consumes: completed diff and the single Gradle attempt output.
- Produces: review fix round 1 evidence and one clean commit.

- [ ] **Step 1: Verify without retrying Gradle after an environment block**

  Strict-decode every changed Kotlin/Markdown file as UTF-8, inspect cached Android APIs used by the patch, inspect the complete diff and call sites, and run `git diff --check`.

- [ ] **Step 2: Update the Task 11 report**

  Append a `Fix round 1` section documenting each behavior test, minimal production change, the exact dynamic-test result or environment blocker, static API/UTF-8/diff evidence, and the explicit Task12/connected/adb/local.properties exclusions.

- [ ] **Step 3: Commit and confirm cleanliness**

  Force-add the ignored Task 11 report together with tracked source/tests/plan, commit as `fix: fail closed automatic bookkeeping settings`, and verify `git status --short --branch` is clean.
