# 编辑记录分类选择与可选商户实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将编辑页的横向分类长列表替换为独立四列图标网格选择页，并允许所有记录在商户留空时按当前收入或支出方向保存。

**Architecture:** 保持 `EntryViewModel` 为唯一草稿所有者；分类选择页只读取 `EntryUiState.categoryChoices` 并通过回调更新草稿，不写数据库。`AutoBookkeepingRoot` 用可保存的编辑子页面状态在编辑页和分类页之间切换，并在分类页隐藏底部主导航；图标与搜索使用无状态纯函数，便于 JVM 测试。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Lifecycle ViewModel/StateFlow、Room 现有仓储、JUnit 5、Compose UI Test。

## Global Constraints

- 分类选择页是 `ENTRY` 内部子页面，不新增数据库表、字段或迁移。
- 页面使用四列网格、图标加文字；点击分类立即返回，不显示“完成”按钮。
- 默认分类使用稳定图标映射；未映射分类使用首个可见字符，无法取得时使用通用标记。
- 商户为选填；空值保持当前 `Direction.EXPENSE` 或 `Direction.INCOME`，不得阻止保存。
- 空商户以空字符串保存，不写占位文字，不创建或更新商户记忆规则。
- 金额和可用分类仍为必填；现有账户、时间、备注、内部转账、去重和原子确认语义不变。
- 当前隐藏历史分类可保留并单独标注；其他隐藏分类不出现在选择页。
- 分类选择页不显示底部主导航；系统返回和顶部返回不修改原分类。
- 三个现有主题使用相同布局及主题色，不添加新依赖。
- 不记录或输出商户、金额、页面 OCR 原文、截图或数据库内容。
- 不运行 `connectedDebugAndroidTest`；所有 ADB 命令必须由 10 秒外层超时保护。

---

## 文件结构

- `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt`
  - 仅负责草稿、校验和保存；移除待确认记录的商户必填条件。
- `app/src/main/java/com/local/autobookkeeping/ui/entry/CategorySelectorScreen.kt`
  - 新增分类图标映射、搜索过滤纯函数和独立分类选择页面。
- `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryScreen.kt`
  - 用单行分类入口替换横向 `LazyRow`，通过回调请求打开选择页。
- `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
  - 在 `AutoBookkeepingRoot` 内保存编辑子页面状态、处理返回、隐藏底栏并接线选择回调。
- `app/src/test/java/com/local/autobookkeeping/ui/entry/EntryViewModelTest.kt`
  - 覆盖空商户确认、方向保留和现有必填校验。
- `app/src/test/java/com/local/autobookkeeping/ui/entry/CategorySelectorTest.kt`
  - 新增图标回退、搜索和历史分类展示纯逻辑测试。
- `app/src/androidTest/java/com/local/autobookkeeping/EntryCategorySelectorScreenTest.kt`
  - 新增独立选择页的网格、选中语义、搜索、返回和主题测试。
- `app/src/androidTest/java/com/local/autobookkeeping/EndToEndTest.kt`
  - 更新手动记账旅程，改为通过分类入口选择分类，并验证空商户可保存。

---

### Task 1: 允许待确认记录在商户留空时保存

**Files:**
- Modify: `app/src/test/java/com/local/autobookkeeping/ui/entry/EntryViewModelTest.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt`
- Verify: `app/src/androidTest/java/com/local/autobookkeeping/data/RecognitionStoreTest.kt`

**Interfaces:**
- Consumes: `EntryDraft.toTransaction(confirmPending: Boolean): Transaction`、`RecognitionConfirmationOperations.confirmAndRemember(Transaction): Long`。
- Produces: 商户空白不再是校验错误；金额、分类和方向语义保持原样。

- [ ] **Step 1: 写空商户待确认记录的失败测试**

在 `EntryViewModelTest` 增加：

```kotlin
@Test
fun `blank merchant pending record confirms with selected direction and category`() =
    runTest(dispatcher) {
        val confirmation = FakeRecognitionConfirmation()
        val viewModel = EntryViewModel(
            repository = DelayedRepository(releaseImmediately = true),
            recognitionConfirmation = confirmation,
        )
        viewModel.edit(
            pendingTelecomTransaction().copy(
                merchant = "   ",
                direction = Direction.INCOME,
            ),
        )
        viewModel.update { it.copy(category = "其它") }

        val result = viewModel.confirmPending()

        assertTrue(result.isSuccess)
        assertEquals("", confirmation.saved.single().merchant)
        assertEquals(Direction.INCOME, confirmation.saved.single().direction)
        assertEquals("其它", confirmation.saved.single().category)
        assertEquals(false, confirmation.saved.single().pendingConfirmation)
    }
```

再增加两个直接校验用例，证明空商户不放宽金额和分类：

```kotlin
@Test
fun `blank merchant does not bypass amount and category validation`() {
    val blankAmount = EntryDraft(amountText = "", category = "其它", merchant = "")
    val blankCategory = EntryDraft(amountText = "1.00", category = "", merchant = "")

    assertThrows<EntryValidationException> { blankAmount.toTransaction(confirmPending = true) }
    assertThrows<EntryValidationException> { blankCategory.toTransaction(confirmPending = true) }
}
```

- [ ] **Step 2: 运行测试并确认因商户必填失败**

运行：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='E:\软件开发\DevTools\Android\sdk'
$env:GRADLE_USER_HOME='C:\Users\35020\.gradle'
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.ui.entry.EntryViewModelTest" --no-daemon --max-workers=1 --console=plain
```

预期：`blank merchant pending record confirms...` 失败，错误为现有“请填写商户并选择分类”。

- [ ] **Step 3: 最小移除商户必填条件**

在 `EntryDraft.toTransaction` 删除：

```kotlin
if (pendingConfirmation && confirmPending && (merchant.isBlank() || category.isBlank())) {
    throw EntryValidationException("确认待核对记录前，请填写商户并选择分类")
}
```

保留现有：

```kotlin
if (category.isBlank()) throw EntryValidationException("请选择分类")
merchant = merchant.trim()
direction = direction
```

不修改 `RoomRecognitionStore.confirmAndRemember`；其现有逻辑已经在商户空白时跳过商户规则写入。

- [ ] **Step 4: 运行聚焦测试并确认通过**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.ui.entry.EntryViewModelTest" --no-daemon --max-workers=1 --console=plain
```

预期：`EntryViewModelTest` 全部通过。

- [ ] **Step 5: 编译现有 Room 空商户契约测试**

运行：

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

预期：编译通过；`RecognitionStoreTest.confirmAndRememberUpsertsNormalizedRuleWhileBlankMerchantCreatesNone` 保持存在且可编译。

- [ ] **Step 6: 提交 Task 1**

```powershell
git add app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt app/src/test/java/com/local/autobookkeeping/ui/entry/EntryViewModelTest.kt
git commit -m "fix: allow blank merchants when confirming entries"
```

---

### Task 2: 分类展示纯逻辑与独立选择页面

**Files:**
- Create: `app/src/main/java/com/local/autobookkeeping/ui/entry/CategorySelectorScreen.kt`
- Create: `app/src/test/java/com/local/autobookkeeping/ui/entry/CategorySelectorTest.kt`
- Create: `app/src/androidTest/java/com/local/autobookkeeping/EntryCategorySelectorScreenTest.kt`

**Interfaces:**
- Consumes: `EntryChoice(name: String, historicalOnly: Boolean)`。
- Produces:
  - `internal fun categoryIconLabel(name: String): String`
  - `internal fun filterCategoryChoices(choices: List<EntryChoice>, query: String): List<EntryChoice>`
  - `internal fun defaultEntryCategoryChoices(): List<EntryChoice>`
  - `@Composable fun CategorySelectorScreen(choices, selectedCategory, loading, onSelect, onBack, modifier)`。

- [ ] **Step 1: 写图标、搜索和历史项纯逻辑失败测试**

创建 `CategorySelectorTest.kt`：

```kotlin
class CategorySelectorTest {
    @Test
    fun `default categories use stable icons and custom category uses first visible character`() {
        assertEquals("🍜", categoryIconLabel("餐饮"))
        assertEquals("🚕", categoryIconLabel("交通"))
        assertEquals("自", categoryIconLabel("  自定义  "))
        assertEquals("•••", categoryIconLabel("   "))
    }

    @Test
    fun `search ignores case and preserves current historical choice only`() {
        val choices = listOf(
            EntryChoice("餐饮", historicalOnly = false),
            EntryChoice("Office", historicalOnly = false),
            EntryChoice("旧分类", historicalOnly = true),
        )

        assertEquals(listOf("Office"), filterCategoryChoices(choices, "off").map { it.name })
        assertEquals(listOf("餐饮", "Office", "旧分类"), filterCategoryChoices(choices, "").map { it.name })
    }
}
```

- [ ] **Step 2: 运行 JVM 测试并确认缺少接口**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.ui.entry.CategorySelectorTest" --no-daemon --max-workers=1 --console=plain
```

预期：编译失败，缺少 `categoryIconLabel` 和 `filterCategoryChoices`。

- [ ] **Step 3: 实现最小纯函数**

在新文件 `CategorySelectorScreen.kt` 中先实现：

```kotlin
private val DEFAULT_CATEGORY_ICONS = mapOf(
    "餐饮" to "🍜", "交通" to "🚕", "日用" to "🧴", "购物" to "🛍",
    "零食" to "🍦", "饮品" to "🥤", "蔬菜" to "🥬", "水果" to "🍓",
    "服饰" to "👕", "娱乐" to "🎵", "美容" to "💄", "通讯" to "☎",
    "医疗" to "💊", "学习" to "🎓", "游戏" to "🎮", "红包" to "🧧",
    "住房" to "🏠", "礼品" to "🎁", "宠物" to "🐾", "汽车" to "🚗",
    "数码" to "📷", "办公" to "💼", "运动" to "🏓", "礼金" to "✉",
    "捐赠" to "❤", "彩票" to "🎫", "转账" to "⇄", "其它" to "•••",
)

internal fun categoryIconLabel(name: String): String =
    DEFAULT_CATEGORY_ICONS[name.trim()]
        ?: name.trim().codePoints().findFirst().orElse(-1)
            .takeIf { it >= 0 }
            ?.let(Character::toChars)
            ?.concatToString()
        ?: "•••"

internal fun filterCategoryChoices(
    choices: List<EntryChoice>,
    query: String,
): List<EntryChoice> {
    val normalized = query.trim()
    return if (normalized.isEmpty()) choices else choices.filter {
        it.name.contains(normalized, ignoreCase = true)
    }
}

internal fun defaultEntryCategoryChoices(): List<EntryChoice> =
    DEFAULT_CATEGORY_ICONS.keys.map { EntryChoice(it, historicalOnly = false) }
```

- [ ] **Step 4: 运行 JVM 测试并确认通过**

运行同 Step 2；预期 `CategorySelectorTest` 全部通过。

- [ ] **Step 5: 写 Compose 选择页失败测试**

创建 `EntryCategorySelectorScreenTest.kt`，使用 `createComposeRule()` 和 `AutoBookkeepingTheme`，覆盖：

```kotlin
@Test
fun categoryGridSearchSelectionAndBackAreAccessible() {
    var selected: String? = null
    var backCalls = 0
    compose.setContent {
        AutoBookkeepingTheme(ThemeStyle.CLEAR_MINT) {
            CategorySelectorScreen(
                choices = listOf(
                    EntryChoice("餐饮", false),
                    EntryChoice("交通", false),
                    EntryChoice("旧分类", true),
                ),
                selectedCategory = "餐饮",
                loading = false,
                onSelect = { selected = it },
                onBack = { backCalls += 1 },
            )
        }
    }

    compose.onNodeWithTag("category-grid").assertIsDisplayed()
    compose.onNodeWithTag("category-choice-餐饮").assertIsSelected()
    compose.onNodeWithTag("category-search").performTextInput("交通")
    compose.onNodeWithTag("category-choice-交通").performClick()
    assertEquals("交通", selected)
    compose.onNodeWithContentDescription("返回编辑记录").performClick()
    assertEquals(1, backCalls)
}
```

另参数化三个 `ThemeStyle`，断言标题、搜索框和网格均可见；搜索无结果显示“没有匹配的分类”。

- [ ] **Step 6: 编译 Android 测试并确认缺少页面**

运行：

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

预期：编译失败，缺少 `CategorySelectorScreen` 或页面测试标签。

- [ ] **Step 7: 实现独立选择页面**

页面使用：

```kotlin
@Composable
fun CategorySelectorScreen(
    choices: List<EntryChoice>,
    selectedCategory: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(choices, query) { filterCategoryChoices(choices, query) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // 小标题栏：返回编辑记录 + 选择分类
        // testTag("category-search") 的 OutlinedTextField
        if (loading) {
            CircularProgressIndicator(Modifier.testTag("category-loading"))
        } else if (filtered.isEmpty()) {
            Text(if (query.isBlank()) "暂无可用分类" else "没有匹配的分类")
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().testTag("category-grid"),
        ) {
            items(filtered, key = { "${it.name}-${it.historicalOnly}" }) { choice ->
                CategoryChoiceCell(
                    choice = choice,
                    selected = choice.name == selectedCategory,
                    onClick = { onSelect(choice.name) },
                    modifier = Modifier
                        .testTag("category-choice-${choice.name}")
                        .semantics { this.selected = choice.name == selectedCategory },
                )
            }
        }
    }
}
```

`CategoryChoiceCell` 的最小点击区域为 48dp；历史项文字追加“历史分类”；无结果显示固定短文案。顶部和系统返回均调用 `onBack`，不调用 `onSelect`。`loading=true` 时显示轻量加载指示器、隐藏网格并保留返回按钮。

- [ ] **Step 8: 编译 Android 测试并运行 JVM 回归**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.ui.entry.CategorySelectorTest" :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 9: 提交 Task 2**

```powershell
git add app/src/main/java/com/local/autobookkeeping/ui/entry/CategorySelectorScreen.kt app/src/test/java/com/local/autobookkeeping/ui/entry/CategorySelectorTest.kt app/src/androidTest/java/com/local/autobookkeeping/EntryCategorySelectorScreenTest.kt
git commit -m "feat: add category selection screen"
```

---

### Task 3: 将分类选择页接入编辑流程

**Files:**
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryScreen.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt`
- Modify: `app/src/main/java/com/local/autobookkeeping/MainActivity.kt`
- Modify: `app/src/androidTest/java/com/local/autobookkeeping/EndToEndTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `CategorySelectorScreen`、`EntryUiState.categoryChoices` 和 `EntryViewModel.update`。
- Produces: `EntryScreen(..., onOpenCategorySelector: () -> Unit, ...)`；`EntryUiState.categoryChoicesLoaded: Boolean`；`AutoBookkeepingRoot` 内完整的编辑/分类子页面导航。

- [ ] **Step 1: 更新端到端测试以描述新流程**

将 `EndToEndTest.createManualTransaction` 的旧横向列表操作：

```kotlin
compose.onNodeWithTag("entry-category-list")
    .performScrollToNode(hasText("红包"))
compose.onNodeWithText("红包").performClick()
```

替换为：

```kotlin
compose.onNodeWithTag("entry-category-field").performScrollTo().performClick()
compose.onNodeWithTag("category-grid").assertIsDisplayed()
compose.onAllNodesWithTag("nav-HOME").assertCountEquals(0)
compose.onNodeWithTag("category-choice-红包").performClick()
compose.onNodeWithTag("entry-category-field").assertTextContains("红包")
```

新增一笔不输入商户的收入记录，保存后断言账单金额为收入，证明分类子页面没有清空金额、方向或日期。

- [ ] **Step 2: 编译 Android 测试并确认旧 UI 不满足契约**

运行：

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

预期：测试可编译，但若执行将找不到 `entry-category-field`；若先引用了新 `EntryScreen` 参数，则在生产代码未修改时编译失败。

- [ ] **Step 3: 用单行分类入口替换横向列表**

修改 `EntryScreen` 签名：

```kotlin
fun EntryScreen(
    state: EntryUiState,
    onUpdate: ((EntryDraft) -> EntryDraft) -> Unit,
    onOpenCategorySelector: () -> Unit,
    onSave: (Boolean) -> Unit,
    // 其余参数不变
)
```

删除 `defaultCategories` 的页面内展开和 `LazyRow` 分类格，改为：

```kotlin
Surface(
    onClick = onOpenCategorySelector,
    modifier = Modifier.fillMaxWidth().testTag("entry-category-field"),
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("分类")
        Text("${draft.category.ifBlank { "请选择分类" }}  ›")
    }
}
```

保留 `state.categoryChoices` 作为选择页数据源。把 `defaultCategories` 移至 `CategorySelectorScreen.kt` 或内部纯函数，供未管理配置状态使用，避免重复两套默认集合。

- [ ] **Step 4: 在 Root 增加可保存子页面状态和返回优先级**

先在 `EntryUiState` 增加：

```kotlin
val categoryChoicesLoaded: Boolean = !configurationManaged
```

`EntryViewModel` 第一次收到 `settings.visibleCategories` 时将该字段置为 `true`；`startNew`、`edit` 和 `refreshManagedChoices` 必须保留该状态。增加 JVM 测试，使用 `StandardTestDispatcher` 证明首次分类流发射后 `categoryChoicesLoaded == true`，且空的真实分类集合显示为空而不是永远加载。

在 `AutoBookkeepingRoot` 增加：

```kotlin
var categorySelectorOpen by rememberSaveable { mutableStateOf(false) }
val isCategorySelector = current == AppDestination.ENTRY && categorySelectorOpen

BackHandler(
    enabled = activeReview == null && (isCategorySelector || navigator.canHandleBack),
) {
    when {
        isCategorySelector -> categorySelectorOpen = false
        navigator.isStatisticsDrilldownActive && current == AppDestination.LEDGER -> {
            ledgerViewModel.closeStatisticsDrilldown()
            navigator.handleBack()
        }
        else -> navigator.handleBack()
    }
}
```

`Scaffold.bottomBar` 仅在 `!isCategorySelector` 时渲染 `NavigationBar`。`AppDestination.ENTRY` 分支改为：

```kotlin
if (categorySelectorOpen) {
    CategorySelectorScreen(
        choices = if (entryState.configurationManaged) {
            entryState.categoryChoices
        } else {
            defaultEntryCategoryChoices()
        },
        selectedCategory = entryState.draft.category,
        loading = entryState.configurationManaged && !entryState.categoryChoicesLoaded,
        onSelect = { category ->
            entryViewModel.update { it.copy(category = category) }
            categorySelectorOpen = false
        },
        onBack = { categorySelectorOpen = false },
    )
} else {
    EntryScreen(
        state = entryState,
        onUpdate = entryViewModel::update,
        onOpenCategorySelector = { categorySelectorOpen = true },
        // 其余回调保持现状
    )
}
```

新建、编辑、保存成功、删除成功或导航离开 `ENTRY` 时把 `categorySelectorOpen` 设为 `false`，防止旧子页面状态泄漏到下一笔记录。

- [ ] **Step 5: 编译并运行聚焦 JVM 测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.local.autobookkeeping.ui.entry.*" :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=1 --console=plain
```

预期：BUILD SUCCESSFUL；Android 测试源码包含新端到端路径且可编译。

- [ ] **Step 6: 静态检查导航和隐私边界**

确认：

```powershell
git diff --check
Select-String -Path app/src/main/AndroidManifest.xml -Pattern 'INTERNET|SYSTEM_ALERT_WINDOW|MANAGE_EXTERNAL_STORAGE'
Get-ChildItem app/src/main -Recurse -File | Select-String -Pattern 'pageRawText|notificationBody|ABK_ALIPAY_ACCEPTANCE'
```

预期：`git diff --check` 无输出；禁止权限和临时诊断标签无命中；不新增原始识别文本持久化。

- [ ] **Step 7: 提交 Task 3**

```powershell
git add app/src/main/java/com/local/autobookkeeping/ui/entry/EntryScreen.kt app/src/main/java/com/local/autobookkeeping/ui/entry/EntryViewModel.kt app/src/main/java/com/local/autobookkeeping/MainActivity.kt app/src/test/java/com/local/autobookkeeping/ui/entry/EntryViewModelTest.kt app/src/androidTest/java/com/local/autobookkeeping/EndToEndTest.kt
git commit -m "feat: open category picker from entry editor"
```

---

### Task 4: 全量回归、APK 与设备验收

**Files:**
- Modify only if evidence requires: `docs/testing/manual-android-checklist.md`
- Update ignored evidence: `.superpowers/sdd/2026-07-29-wechat-alipay-auto-and-screenshot-bookkeeping/progress.md`

**Interfaces:**
- Consumes: Tasks 1–3 的最终提交。
- Produces: fresh JVM、Lint、AndroidTest 编译、APK 和真机手动验收证据。

- [ ] **Step 1: Fresh JVM 全量测试**

先确保没有并行 Gradle，再运行：

```powershell
.\gradlew.bat clean :app:testDebugUnitTest --no-daemon --max-workers=1 --console=plain
```

外层总超时 180 秒。预期：BUILD SUCCESSFUL；汇总 0 failures、0 errors、0 skipped。

- [ ] **Step 2: Lint**

```powershell
.\gradlew.bat :app:lintDebug --no-daemon --max-workers=1 --console=plain
```

外层总超时 180 秒。预期：0 errors；记录 warning 数量，不因既有 warning 宣称失败。

- [ ] **Step 3: Android 测试编译与 APK**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=1 --console=plain
```

外层总超时 180 秒。预期：BUILD SUCCESSFUL，生成 `app/build/outputs/apk/debug/app-debug.apk`；记录大小与 SHA-256。

- [ ] **Step 4: 最终静态审计**

检查：

- 最终工作树 UTF-8 严格解码，无 BOM、U+FFFD 或 NUL；
- `git diff --check` 为 0；
- merged manifest 权限集合未扩大；
- 无 `ABK_ALIPAY_ACCEPTANCE`、原始 OCR/通知正文/截图日志；
- `local.properties` 未创建或修改；
- 应用名称和图标未在本计划中改动。

- [ ] **Step 5: 安装并进行真机手动验收**

仅在设备 `81c74ce1` 显示为 `device` 时，用外层 `Process.WaitForExit(10000)` 执行：

```text
adb -s 81c74ce1 install -r app-debug.apk
```

不重启 ADB、不卸载、不清数据、不运行 connected tests。覆盖安装后若系统关闭无障碍服务，引导用户手动重新开启。

手动验收：

1. 新建支出，输入金额但不填商户；打开分类页选择“其它”，应立即返回并成功保存为支出。
2. 新建收入，输入金额但不填商户；选择“红包”，应成功保存为收入。
3. 编辑刚才的记录，打开分类页，验证当前分类选中；搜索另一分类并选择，其他字段不变。
4. 在分类页按系统返回，分类不变；底部主导航在分类页不可见。
5. 打开一笔自动识别待确认记录，清空商户、选择分类并确认；记录保存且同一识别不重复新增。

- [ ] **Step 6: 更新证据并提交**

只写脱敏结果和计数，不写金额、商户或页面文字：

```powershell
git add docs/testing/manual-android-checklist.md
git commit -m "test: verify category selection and blank merchants"
```

如果 checklist 无需变化，则不制造空提交；把运行证据写入被忽略的 progress 文件，并保持 `git status --short` 为空。

---

## 最终完成条件

- Tasks 1–3 每项都有明确 RED、最小 GREEN 和独立提交。
- Fresh JVM、Lint、AndroidTest 编译和 APK 构建全部在最终提交树通过。
- 真机确认支出、收入和待确认记录在商户为空时均可保存。
- 分类选择页为四列 A1 图标文字布局，点击即返回，底部主导航隐藏。
- 工作树干净，无临时诊断、隐私数据、额外权限或范围外品牌改动。
