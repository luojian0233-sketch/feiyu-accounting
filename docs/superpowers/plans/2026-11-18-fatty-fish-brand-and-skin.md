# 肥鱼记账品牌与肥鱼皮肤 Implementation Plan

> **For agentic workers:** 使用复选框（`- [ ]`）跟踪进度，或把状态写入 `.superpowers/sdd/2026-11-18-fatty-fish-brand-and-skin/progress.md`。

**Goal:** 把应用显示名改为“肥鱼记账”，启动器图标改成大肥鱼，并新增一套带肥鱼元素的简约皮肤。

**Architecture:** 品牌只走 Android 资源层（`strings.xml` + `mipmap`/`drawable` 矢量）；皮肤只走既有 `ThemeStyle` 枚举 + `AutoBookkeepingTheme` 的配色/形状/视觉令牌分支，加一个 `showFishMascot` 令牌控制首页肥鱼。没有新依赖、新权限、新持久化结构。

**Tech Stack:** Android manifest/resources, VectorDrawable, AdaptiveIcon XML, Jetpack Compose (Material3), Kotlin/JUnit source-contract tests, Gradle。

## Global Constraints

- 显示名称固定“肥鱼记账”，只经 `@string/app_name`。
- 图标纯矢量：无位图、无网络资源、无 `<text`、无 `¥`/`$`/`pig`/`coin`/`calculator`。
- 不改权限、依赖、数据库、备份格式、通知识别、无障碍、截图 OCR、服务名称、快捷设置磁贴名称。
- 不改默认主题（仍为 `CLEAR_MINT`），已有主题偏好必须保持可读。
- 不创建/修改 `local.properties`；不跑 connected 测试。
- Gradle 一律从 `R:\`（`subst R:` 到 worktree）执行，`JAVA_HOME` 指向 Android Studio JBR。

---

### Task 1: 改名 + 胖鱼图标（TDD）

**Files:**
- Modify: `app/src/test/java/com/local/autobookkeeping/BrandIdentityContractTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Modify: `app/src/main/res/drawable/ic_launcher_background.xml`
- Modify: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`

**Interfaces:**
- Produces: `@string/app_name` = 肥鱼记账；`@mipmap/ic_launcher`（自适应 + pre-26 回退）指向鱼形矢量。

- [ ] **Step 1: 先把合同测试改成肥鱼期望**

把 `BrandIdentityContractTest` 的断言改为 `assertEquals("肥鱼记账", stringValue(strings, "app_name"))`，
测试方法改名为 `launcher uses the approved Feiyu name and fish mark`，并追加
`assertTrue(foreground.contains("path"))` 与“前景不得只有纯背景色矩形”的断言（可选）。

- [ ] **Step 2: 跑测试确认 RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.BrandIdentityContractTest" --no-daemon --max-workers=1 --console=plain
```

Expected: FAIL —— `expected:<肥鱼记账> but was:<拾账>`。

- [ ] **Step 3: 改名字符串并重绘图标**

1. `strings.xml` 的 `app_name` 改为 `肥鱼记账`。
2. 背景矢量：`#CDEFF4` 纯色铺满 108×108。
3. 前景矢量（108×108，内容限制在 21..87）：
   - 奶白胖椭圆身体（cx 52, cy 54, rx 20, ry 16）；
   - 右侧两瓣扇形尾鳍；
   - 上背鳍、下腹鳍各一枚小弧三角；
   - 深青眼睛 + 白高光；
   - 珊瑚腮红小圆。
4. pre-26 `mipmap-anydpi/ic_launcher.xml` 用同一构图（可简化尾鳍细节），底色同为清水色。

- [ ] **Step 4: 跑合同测试与资源编译确认 GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.BrandIdentityContractTest" :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/res app/src/test/java/com/local/autobookkeeping/BrandIdentityContractTest.kt
git commit -m "feat: rebrand to feiyu bookkeeping with fish launcher icon"
```

### Task 2: 肥鱼简约皮肤

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/theme/AppTheme.kt`
- Create: `app/src/main/java/com/local/autobookkeeping/ui/theme/FishMascot.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/home/HomeScreen.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/ui/theme/ThemeCatalogContractTest.kt`

**Interfaces:**
- `ThemeStyle.FATTY_FISH`：新增枚举值，永久保留名称（备份文件按 name 校验）。
- `AppVisualTokens.showFishMascot: Boolean`：默认 `false`，只有 `FATTY_FISH` 为 `true`。
- `FishMascot(modifier, tint)`：Canvas 绘制的胖鱼，无文字、无点击语义。

- [ ] **Step 1: 先写失败的主题目录测试**

`ThemeCatalogContractTest` 断言：`ThemeStyle.entries` 含 `FATTY_FISH`；四套主题名称互不重复；
`CLEAR_MINT` 仍是枚举首项（默认主题不变）。

- [ ] **Step 2: 跑测试确认 RED**（编译期即失败：`FATTY_FISH` 不存在）

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.ui.theme.ThemeCatalogContractTest" --no-daemon --max-workers=1 --console=plain
```

- [ ] **Step 3: 实现皮肤**

1. `ThemeStyle` 增加 `FATTY_FISH`（放在最后，保持既有序号/名称不变）。
2. `AppTheme.kt`：新增 `fattyFishColors` 浅色方案、圆润 `Shapes`、`AppVisualTokens(showFishMascot = true)`；
   给 `AppVisualTokens` 增加 `showFishMascot: Boolean = false` 字段，并更新所有既有分支（显式 `false`）。
3. `FishMascot.kt`：Canvas 画椭圆身体 + 扇形尾 + 背鳍/腹鳍 + 眼睛 + 腮红，颜色取 `colorScheme.primary`
   与固定奶白/珊瑚，尺寸由 modifier 决定（首页用 26dp）。
4. `SettingsScreen.kt`：`ThemeStyle.label` 增加 `FATTY_FISH -> "肥鱼简约"`。
5. `HomeScreen.kt`：标题行由单纯 `Text("今天")` 改为 `Row`，右端在 `appVisualTokens.showFishMascot` 为真时
   显示 `FishMascot`，其余皮肤不产生额外节点。

- [ ] **Step 4: 跑全量 JVM 测试与构建**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

Expected: 全绿；APK 生成。

- [ ] **Step 5: 提交**

```powershell
git add app/src/main/java/com/local/autobookkeeping/ui app/src/test/java/com/local/autobookkeeping/ui
git commit -m "feat: add fatty fish minimalist theme skin"
```

### Task 3: 文档与产物核验

- [ ] 更新 `README.md`：标题改“肥鱼记账”，主题章节补第四套皮肤，构建产物 SHA-256 更新。
- [ ] `aapt2 dump badging` 确认 `application-label:'肥鱼记账'` 且 `application-icon-*` 指向 `ic_launcher.xml`。
- [ ] `git status --short` 为空；`.superpowers/sdd/2026-11-18-fatty-fish-brand-and-skin/progress.md` 记录结果。

## Plan self-review

- Spec 覆盖：名称、图标（自适应 + 回退 + 无文字）、皮肤（配色/形状/令牌/首页肥鱼）、兼容性都有对应任务。
- 无占位符：所有文件路径、资源名、颜色值、命令、提交信息均已具体化。
- 类型一致：`FATTY_FISH`、`showFishMascot`、`FishMascot` 在 Task 2 中定义并只在 Task 2 消费。
