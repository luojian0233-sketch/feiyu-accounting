# Production Theme Recreation Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the in-memory custom-Activity theme test with an isolated test that launches production `MainActivity` through the production graph and real Room/DataStore persistence.

**Architecture:** Production startup receives one protected graph factory and provider defaults that preserve existing behavior. A custom instrumentation runner creates a test Application whose graph uses uniquely named, file-backed Room and Preferences DataStore resources, which the test seeds, verifies across Activity recreation, and cleans.

**Tech Stack:** Kotlin, AndroidX ActivityScenario, Compose UI Test, Room, Preferences DataStore, JUnit 4 instrumentation, JUnit 5 JVM source checks.

## Global Constraints

- Change only Task 8 Finding 7.
- Do not add companion/global mutable production overrides.
- Production defaults must continue to use the production Room database and Preferences DataStore.
- Launch `MainActivity` directly; do not use a custom test Activity or fake ledger repository.
- Use isolated real Room/DataStore files and clean them after the test.
- Do not change cancellation/error behavior or log sensitive content.

---

### Task 1: Production MainActivity Persistence Test

**Files:**
- Create: `app/src/test/java/com/local/autobookkeeping/ThemeSwitchTestStructureTest.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/AutoBookkeepingApplication.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/data/SettingsRepository.kt`
- Modify: `app/build.gradle.kts`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTestRunner.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTestApplication.kt`
- Modify: `app/src/androidTest/java/com/local/autobookkeeping/ThemeSwitchTest.kt`
- Delete: `app/src/androidTest/AndroidManifest.xml`
- Modify: `.superpowers/sdd/2026-07-22-android-auto-bookkeeping/task-8-report.md`

**Interfaces:**
- Produces: `protected open fun createApplicationGraph(): ApplicationGraph`
- Produces: provider parameters on `ApplicationGraph` for `AppDatabase` and `SettingsRepository`
- Produces: `SettingsRepository(dataStore: DataStore<Preferences>, database: AppDatabase)`
- Produces: `ThemeSwitchTestRunner` and `ThemeSwitchTestApplication`
- Consumes: production `MainActivity`, `RoomLedgerRepository`, `SettingsRepository`, `AppDatabase`, and `ActivityScenario`

- [ ] **Step 1: Write the failing structure test**

Add a JVM test that loads the Android test source and asserts:

```kotlin
assertTrue(themeSource.contains("ActivityScenario.launch(MainActivity::class.java)"))
assertFalse(themeSource.contains("ThemeSwitchTestActivity"))
assertFalse(themeSource.contains("ThemeLedgerRepository"))
assertFalse(themeSource.contains("MutableStateFlow"))
assertTrue(runnerSource.contains("ThemeSwitchTestApplication"))
assertTrue(testApplicationSource.contains("Room.databaseBuilder"))
assertTrue(testApplicationSource.contains("PreferenceDataStoreFactory.create"))
```

- [ ] **Step 2: Verify RED in a physical detached worktree**

Run:

```text
gradle.bat :app:testDebugUnitTest --tests '*ThemeSwitchTestStructureTest' --console=plain
```

Expected: FAIL because the old test uses `ThemeSwitchTestActivity`,
`ThemeLedgerRepository`, and `MutableStateFlow`, and does not launch
`MainActivity`.

- [ ] **Step 3: Add the narrow production graph seam**

Use:

```kotlin
open class AutoBookkeepingApplication : Application() {
    val applicationGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createApplicationGraph()
    }

    protected open fun createApplicationGraph(): ApplicationGraph =
        ApplicationGraph(this)
}
```

Give `ApplicationGraph` defaulted providers whose default lambdas call the
existing production factories. Add a `SettingsRepository` constructor that
combines a supplied Preferences DataStore with a supplied `AppDatabase`.

- [ ] **Step 4: Replace the old instrumentation fixture**

Configure `ThemeSwitchTestRunner` in `defaultConfig`. Its
`newApplication` returns `ThemeSwitchTestApplication`. The test Application
creates unique file-backed Room/DataStore resources, overrides
`createApplicationGraph`, and exposes cleanup that cancels its DataStore scope,
closes Room, and deletes the exact isolated files.

Replace `ThemeSwitchTest` with an empty Compose rule plus:

```kotlin
scenario = ActivityScenario.launch(MainActivity::class.java)
```

Seed a transaction through `applicationGraph.ledgerRepository`, navigate to
ledger/settings/statistics, choose `PAPER_MINIMAL`, recreate, then assert theme,
destination, UI transaction text, and repository transaction persistence.

- [ ] **Step 5: Verify GREEN**

Run the focused structure test and:

```text
gradle.bat :app:assembleDebugAndroidTest --console=plain
```

Expected: both commands exit 0.

- [ ] **Step 6: Verify all deliverables**

Run:

```text
gradle.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

Expected: all JVM tests pass and both APKs build.

- [ ] **Step 7: Commit and report**

Append Fix Round 2 evidence and the final SHA to the Task 8 report, commit the
tracked implementation, verify a clean worktree, and remove the physical
detached verification worktree.
