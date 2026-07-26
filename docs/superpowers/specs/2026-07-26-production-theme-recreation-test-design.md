# Production Theme Recreation Test Design

## Scope

Replace only the Task 8 theme recreation instrumentation test. Findings 1–6
and 8, the deferred minor findings, and Task 9 remain unchanged.

## Architecture

`AutoBookkeepingApplication` gains one protected graph factory method. Its
default implementation constructs the existing production `ApplicationGraph`,
so normal startup continues to use the production Room database and production
Preferences DataStore.

`ApplicationGraph` accepts narrow database and settings-repository providers,
whose defaults are the current production factories. The Android test runner
creates a test subclass of `AutoBookkeepingApplication`; that subclass supplies
an isolated, file-backed Room database and an isolated Preferences DataStore
through those providers. No companion object or mutable global production
override is introduced.

## Test Data Flow

The test application owns uniquely named database and preference files. Before
launching the Activity, the test seeds one transaction through the graph's real
`RoomLedgerRepository`. The test then launches `MainActivity` directly with
`ActivityScenario`, navigates through the production UI, changes the theme,
selects a destination containing persisted app state, and calls
`scenario.recreate()`.

After recreation, the test verifies the selected theme, restored destination,
transaction text in the UI, and the transaction read back through the real
Room repository/DAO. Cleanup closes DataStore/Room scopes and deletes both
isolated files. No `MutableStateFlow`, fake ledger repository, or custom test
Activity remains.

## Failure and Safety Boundaries

Production defaults remain unchanged and the test injection point is protected
and provider-only. Existing cancellation and stable-error behavior is not
modified. Test code does not log transaction or notification contents.

## Verification

A JVM source-structure regression first fails against the old custom
Activity/fake repository test and then passes when the test directly launches
`MainActivity` through the custom runner/application path. Runtime
instrumentation remains deferred when no device is available, but all JVM
tests, the debug APK, and the AndroidTest APK must compile successfully.
