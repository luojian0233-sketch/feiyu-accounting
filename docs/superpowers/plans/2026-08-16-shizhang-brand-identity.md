# 拾账应用名与桌面图标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android 应用显示名称改为“拾账”，并添加清晰、可裁切的“小票＋勾线”自适应启动器图标。

**Architecture:** 品牌资源只通过 Android 资源系统和 Manifest 暴露：字符串资源提供名称，`mipmap` 启动器资源提供 Android 8+ 自适应图标与旧系统矢量回退。图标前景由本地矢量路径组成，不引入位图、网络资源或运行时逻辑。

**Tech Stack:** Android manifest/resources, VectorDrawable, AdaptiveIconDrawable XML, Kotlin/JUnit source-contract tests, Gradle.

## Global Constraints

- 显示名称固定为“拾账”。
- 图标为暖薄荷底、小票、深青勾线和克制珊瑚点；不含文字、货币符号、人物、钱币、猪猪、计算器或外部位图。
- `application` 的 `label`、`icon` 和 `roundIcon` 必须引用资源；不使用硬编码 label。
- 不改主题、页面、服务名称、快捷设置图标、权限、依赖、数据库、自动识别、备份或任意账单数据。
- 不创建或修改 `local.properties`；不运行 connected tests。
- 所有 ADB 命令最多 10 秒；不重启 ADB、不卸载、不清数据。

---

### Task 1: 品牌资源与资源合同

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/com/local/autobookkeeping/BrandIdentityContractTest.kt`

**Interfaces:**
- Consumes: Android manifest `application` resource attributes and resource XML.
- Produces: `@string/app_name`, `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round` (or one explicitly shared launcher resource) and a stable manifest contract.

- [ ] **Step 1: Write the failing source-contract test**

Create `BrandIdentityContractTest` using `DocumentBuilderFactory` and `projectRoot()` as in `PrivacyContractTest`. Parse `app/src/main/AndroidManifest.xml` and `values/strings.xml`, then assert:

```kotlin
assertEquals("@string/app_name", application.getAttributeNS(ANDROID_NAMESPACE, "label"))
assertEquals("@mipmap/ic_launcher", application.getAttributeNS(ANDROID_NAMESPACE, "icon"))
assertEquals("@mipmap/ic_launcher", application.getAttributeNS(ANDROID_NAMESPACE, "roundIcon"))
assertEquals("拾账", stringValue(document, "app_name"))
assertTrue(Files.exists(projectRoot().resolve("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml")))
assertTrue(Files.exists(projectRoot().resolve("app/src/main/res/mipmap-anydpi/ic_launcher.xml")))
```

Also assert the foreground source contains no `<text` tag and no `¥`, `$`, `￥`, `pig`, `coin`, or `calculator` token.

- [ ] **Step 2: Run the source-contract test and verify it fails**

Run from `R:\` with the configured JDK/SDK/Gradle cache:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.BrandIdentityContractTest" --no-daemon --max-workers=1 --console=plain
```

Expected: FAIL because `app_name` and launcher resources do not yet exist, and Manifest still contains the hard-coded old label.

- [ ] **Step 3: Add the minimal brand resources**

1. Add `<string name="app_name">拾账</string>` to `strings.xml`.
2. Replace the application’s literal label with `@string/app_name`; add `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher"`.
3. Add a solid warm-mint background vector and a foreground vector using only paths: rounded warm-white receipt silhouette, three short non-text detail lines, a deep-teal check stroke, and one small coral circle. Keep foreground content inside the adaptive safe area.
4. Add an API 26+ `<adaptive-icon>` resource using the background and foreground drawables. Add a pre-26 `<vector>` fallback at the same launcher name with a solid mint field and the same simplified receipt/check mark.

- [ ] **Step 4: Run the contract test and Android resource compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.BrandIdentityContractTest" :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

Expected: BUILD SUCCESSFUL. The contract verifies name, manifest wiring, adaptive/fallback resources, and the icon’s non-text privacy/brand boundary.

- [ ] **Step 5: Commit the focused deliverable**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/res app/src/test/java/com/local/autobookkeeping/BrandIdentityContractTest.kt
git commit -m "feat: add shizhang app identity"
```

### Task 2: Final APK and device handoff

**Files:**
- Modify only if evidence requires: `docs/testing/manual-android-checklist.md`
- Update ignored evidence: `.superpowers/sdd/2026-08-16-shizhang-brand-identity/task-2-report.md`

**Interfaces:**
- Consumes: Task 1 manifest/resources and `app/build/outputs/apk/debug/app-debug.apk`.
- Produces: build evidence and user-visible verification of “拾账” and the launcher icon.

- [ ] **Step 1: Build the final APK**

Run serially from `R:\`, each command with a 180-second outer timeout:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:lintDebug --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=1 --console=plain
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Expected: JVM tests pass, lint has zero errors, Android test source compilation and APK assembly succeed. Record APK byte size and SHA-256.

- [ ] **Step 2: Audit final change boundaries**

```powershell
git diff --check HEAD~1..HEAD
git diff --name-only HEAD~1..HEAD
```

Expected: only Manifest, strings/icon resources, source-contract test, and evidence/checklist if needed. Confirm no permission/dependency/database/recognition change and no `local.properties`.

- [ ] **Step 3: Install with the 10-second device limit**

First require serial `81c74ce1` to appear as `device` in an ADB process capped by `Process.WaitForExit(10000)`. Then run only:

```text
adb -s 81c74ce1 install -r app-debug.apk
```

Use the same ten-second cap. Do not restart ADB, uninstall, clear app data, run connected tests, or reboot. If the command times out, terminate it and report the last output.

- [ ] **Step 4: Ask for one manual visual check**

Ask the user to inspect the desktop/launcher and confirm that the name is “拾账” and the mint receipt-with-check icon is displayed. If the launcher retains a cached old icon, explain that only launcher cache is stale; do not clear app data.

- [ ] **Step 5: Record evidence and finish**

Write only sanitized timings/counts/hash and visual-check outcome to the ignored report. If no tracked checklist change is required, create no empty commit. End with `git status --short` empty.

## Plan self-review

- Spec coverage: Task 1 covers the name, adaptive and fallback icon, local vector implementation, Manifest wiring, and resource contract. Task 2 covers JVM/lint/build, device installation, launcher validation, and safety limits.
- No placeholders: all resource names, paths, assertions, commands, output conditions, and commit message are concrete.
- Type consistency: `@string/app_name` and `@mipmap/ic_launcher` are defined once in Task 1 and consumed consistently by Task 2.
