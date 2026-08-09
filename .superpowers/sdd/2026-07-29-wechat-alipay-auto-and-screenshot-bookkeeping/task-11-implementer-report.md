# Task 11 实施报告：自动记账设置与权限恢复

## 范围与基线

- BASE：`05547b0b90cb36c56ba20a57aefd8e306bbbc832`。
- 开始时位于 linked worktree `E:\软件开发\自动记账App\.worktrees\android-mvp`、分支 `feature/android-mvp`；工作树 clean。
- 只实施 Task 11。未开始 Task 12+，未运行 connected/adb，未创建或修改 `local.properties`。
- `AutoBookkeepingApplication.kt` 虽未列入 brief 的 Files 清单，但它持有 `RecognitionCoordinator` 与 `AutoSavePolicy` 的实际集成点，因此纳入了 Task 11 所需的最小动态接线。

## TDD 记录

生产实现前先增加/修改了以下行为测试，并逐项记录其预期捕获的缺陷：

1. `SettingsRepositoryTest`
   - 新库默认 total/微信/支付宝关闭，高置信自动保存默认开启。
   - `Preferences.contains` 区分 total key 缺失与显式 `false`。
   - 微信、支付宝或有效银行来源任一既有开启时，将缺失 total 一次性迁移为 `true`；迁移后关闭旧来源不反向关闭 total。
   - total 与高置信偏好写入后可由重建的 repository 读取。
2. `NotificationAccessTest`
   - master 关闭时，即使微信来源开启也在 parser 前返回。
   - master 读取普通异常 fail-closed 并记录脱敏诊断；协程取消继续传播。
3. `AutoPageRecognitionControllerTest`
   - repository adapter 同时要求 master 与精确平台开关；未知包 fail-closed。
   - 原有测试继续约束设置检查发生在截图/OCR/processor 前，设置取消不被吞。
4. `SettingsViewModelTest`
   - resume 刷新通知/无障碍权限并产生稳定中文状态。
   - master 与高置信偏好独立写回。
   - 系统设置/Tile 启动异常只显示稳定中文，不暴露内部路径。
5. `AutomaticBookkeepingAccessTest`
   - 只有 `PaymentPageAccessibilityService` 的 exact `ComponentName` 才算无障碍已开启；同包不同服务、相似包均不匹配。
6. `AutomaticBookkeepingSettingsScreenTest`
   - 在三种 `ThemeStyle` 下验证两卡子页。
   - master 关闭时微信/支付宝禁用，但 master、通知/无障碍恢复、Tile、电池设置与高置信偏好仍可操作。
   - 设置入口与系统 Back 经 `rememberSaveable`/`BackHandler` 返回主设置页。

### 动态执行阻塞

仅进行了一次、20 秒 host timeout 的 focused JVM 命令：

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.data.SettingsRepositoryTest" --tests "com.local.autobookkeeping.ui.settings.SettingsViewModelTest" --tests "com.local.autobookkeeping.notification.NotificationAccessTest" --tests "com.local.autobookkeeping.recognition.accessibility.AutoPageRecognitionControllerTest" --tests "com.local.autobookkeeping.AutomaticBookkeepingAccessTest"
```

命令在 0.8 秒内、进入 Gradle 配置或测试执行前失败：

```text
'"java.exe"' is not recognized as an internal or external command,
operable program or batch file.
```

按照 managed-sandbox handoff，之后没有重试 Gradle、搬运缓存、下载依赖或请求长时间审批。因此本任务的准确表述是：**测试先写，但动态执行被环境阻塞；不宣称 RED/GREEN 或测试通过。**

## 生产实现

### DataStore 与动态策略

- `NotificationSourceSettings`/`SettingsOperations` 新增 `autoBookkeepingEnabled`；`SettingsOperations` 另新增 `autoSaveHighConfidence` 及两个 setter。
- 新 key：`auto_bookkeeping_enabled`、`auto_save_high_confidence`。
- total 首次收集时使用 `Preferences.contains` 判断 key 是否存在；若缺失，在一次原子 `DataStore.edit` 内根据最新微信/支付宝/有效银行来源写入迁移值。显式 `false` 永不被覆盖。
- 高置信偏好默认 `true`。
- `ApplicationGraph` 的 `AutoSavePolicy` 每次决策调用 `settingsRepository.autoSaveHighConfidence.first()`，没有静态缓存；关闭后由现有 `RecognitionCoordinator` 逻辑把原可自动保存的结果写成 pending。

### 自动流程门控

- `PaymentNotificationService` 仍在 payload extraction 前调用 `NotificationRouter.isSourceEnabled()`；router 现先检查 master，再检查微信/支付宝/银行来源，`route()` 在 parser 前再次门控。
- `SettingsAutomaticPageRecognitionSettings` 先检查 master，再读取相同的微信/支付宝 Flow；`AutoPageRecognitionController` 在 `parser.parse()`、capture 与 OCR 前调用该 adapter。
- 设置读取普通异常 fail-closed；`CancellationException` 继续传播。
- 未修改或门控 `ScreenshotRecognitionController`、Quick Settings Tile、MediaProjection 手动路径。

### 权限恢复与设置页

- `AccessibilityAccess` 读取 `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`，并与 `PaymentPageAccessibilityService` 的 exact `ComponentName` 比较。
- `AutomaticBookkeepingAccess` 提供通知使用权、无障碍、Quick Settings Tile 与电池优化设置入口。
- 电池入口仅打开 `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`，不直接请求豁免。
- 启动失败通过布尔/枚举返回给 ViewModel；UI 只显示稳定中文。
- `SettingsViewModel` 收集 total、微信、支付宝与高置信偏好；`refreshAutomaticAccess()` 同时刷新通知/无障碍状态。
- `MainActivity` 在 `ON_RESUME` 调用 `refreshAutomaticAccess()`。
- `SettingsScreen` 使用 `rememberSaveable` 保存子页状态，并用嵌套 `BackHandler` 返回。
- 子页保持两张 `AppSectionCard`，仅使用 `titleMedium`、`bodyMedium`、`bodySmall`；master 不依赖权限，高置信偏好不依赖 master，权限恢复/Tile/电池入口始终不受 master 门控。

## 静态验证

- `git diff --check`：退出码 0；只有 Git 的 Windows LF→CRLF 提示，无 whitespace error。
- Android API 缓存 class 静态检查：
  - `AccessibilityServiceInfo.FEEDBACK_ALL_MASK` 存在；
  - `Settings.ACTION_ACCESSIBILITY_SETTINGS` 存在；
  - `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 存在。
- DataStore API 缓存 class 静态检查：`Preferences.contains` 符号存在。
- source/Manifest 检查：
  - `AndroidManifest.xml` 保持 UTF-8；未增加 INTERNET、存储、overlay 或其他权限；
  - accessibility service 仍仅允许微信/支付宝包，未启用 gesture 能力；
  - 本任务所有新增/修改 Kotlin、XML、Markdown 文件均通过 UTF-8 严格解码检查。
- 接口实现静态复核：所有 `SettingsOperations`、`SettingsPreferenceStore`、`NotificationSourceSettings` 测试 fake 已补齐新成员。

## 未执行项

- 未执行 JVM/Compose instrumentation 的动态测试、lint 或 assemble；原因是上述 Java/Gradle 环境阻塞。
- 未运行 connected/adb；这是任务指令明确禁止的范围。

## Fix round 1（Task 11 review）

### 基线与范围

- 修复基线/原 HEAD：`9ba444dd3738a3d4b9dbfb8d8e83bdd9cf47e6da`；开始时 `feature/android-mvp` 工作树 clean。
- 仅处理三项 review finding：通知设置入口的 application-context 安全启动、自动保存策略 fail-closed、无障碍服务组件名规范化；未开始 Task 12。
- 未运行 connected/adb，未创建、读取或修改 `local.properties`。

### TDD 与行为覆盖

- 生产修复前先补 `AutomaticBookkeepingAccessTest`：通过 Robolectric application context 启动真实 Intent，并断言 `FLAG_ACTIVITY_NEW_TASK`；使用真实 `ResolveInfo`/`ServiceInfo` 数据覆盖完整类名、`.relative` 类名、null resolveInfo、同包错误服务、相似包，以及查询侧 `SecurityException`/普通 `RuntimeException`。
- 生产修复前先补 `NotificationSettingsLauncherTest`：`canOpen` 与 `open` 的普通 `RuntimeException` 均不得泄漏；主入口失败时仍尝试 general settings fallback，所有尝试失败时稳定返回 `false`。
- 生产修复前先补 `SettingsRepositoryTest`：DataStore 数据流抛 `IOException` 时 `autoSaveHighConfidence` 返回 `false`。原有真实 fresh store absent-key=`true` 与用户持久化 `false`=`false` 断言保留。
- 生产修复前新增 `AutoSavePolicyTest`：正常 Flow 值原样返回，`IOException`/普通 `RuntimeException` 返回 `false`，`CancellationException` 原对象重抛。原有 `RecognitionCoordinatorTest.disabled automatic save always creates a reviewable pending row` 继续从真实 outcome、transaction category/pending 状态与 diagnostics 验证 policy false 会落为可复核 pending，而非只断言 mock 调用。

### 最小生产修复

- `AndroidNotificationSettingsLauncher.open()` 为 Intent 添加 `FLAG_ACTIVITY_NEW_TASK`；`openNotificationAccessSafely()` 在 `canOpen` 和 `open` 两个边界捕获 `RuntimeException`，逐项 fallback 后返回布尔结果。
- 高置信自动保存偏好仍对正常 absent key 使用产品默认 `true`，但 DataStore `IOException` 单独 emit `false`。`AutoSavePolicy.failClosed(Flow<Boolean>)` 使用最新一次 `first()` 决策，普通异常返回 `false`、取消继续传播；`ApplicationGraph` 改用该适配器。
- `AccessibilityAccess` 从系统返回的 resolve info 使用 `ComponentName.createRelative(packageName, name)`，null 元数据忽略，完整 `ComponentName` 相等性不变；平台查询和映射的 `SecurityException`/`RuntimeException` 均 fail-closed。

### 验证证据与环境阻塞

- 严格遵守本轮“最多一次、≤30 秒 focused Gradle”约束。唯一命令包含五个相关测试类，0.5 秒内在 Gradle 配置/测试前结束，输出：`'"java.exe"' is not recognized as an internal or external command, operable program or batch file.`；未重试、未提权，因此本轮不宣称动态 RED/GREEN 或测试通过。
- 缓存 API 静态复核确认 `ComponentName.createRelative(String, String)`、`AccessibilityServiceInfo.getResolveInfo()`、`ResolveInfo.serviceInfo`、`ServiceInfo()`、DataStore `data/updateData` 以及 Robolectric `ShadowContextWrapper.getNextStartedActivity()` 符号存在。`javap` 在打印签名后遇到已知 ZipFS `AccessDeniedException`，未影响已输出的符号证据，也未触发 Gradle 重试。
- 完整调用点静态复核确认 `ApplicationGraph` 不再裸调用 `autoSaveHighConfidence.first()`，无障碍 access provider 的异常边界包住系统查询及组件映射，且精确组件身份（包名 + 规范化类名）仍被保留。
- `git diff --check` 退出码 0，仅有 Git 的 LF→CRLF 提示，无 whitespace error；最终提交前另对 staged diff 执行相同检查。
- 本轮新增/修改 Kotlin 与 Markdown 文件均纳入 UTF-8 严格解码检查。
