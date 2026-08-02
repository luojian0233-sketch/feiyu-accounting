# Task 8 实现报告：微信/支付宝无障碍页面识别

## 范围与基线

- 基线：`18c8773f068cc2c94553957ef27ad3653f1a589e`
- 工作区：`R:\` linked worktree，分支 `feature/android-mvp`
- 仅实现 Task 8；未实现 Task 9 或后续截图授权 UI。
- 未创建 `local.properties`；构建仅使用进程级 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT` 和 `GRADLE_USER_HOME`。

## 实现摘要

- 新增只读 `PaymentPageAccessibilityService`：只接收窗口状态/内容变化，代码与 XML 均严格限制为微信 `com.tencent.mm` 和支付宝 `com.eg.android.AlipayGphone`，不声明手势能力，也不调用 gesture/action API。
- 新增 `AccessibilitySnapshotFactory`：仅收集可见节点文本/描述，保留屏幕几何边界；遍历过程中以及异常清理路径均释放所有 `AccessibilityNodeInfo` 引用。
- 新增 `RecognitionDebouncer`：只保存 parser 已确认签名页面的 SHA-256 摘要；使用 in-flight reservation 与 terminal commit / transient release 语义，并在每次 reserve 时清理过期 committed 项，硬上限为 128 条。相同规范化页面并发时仅一个 in-flight，几何变化会形成不同页面身份。
- 新增 `AutoPageRecognitionController`：平台开关读取失败时 fail-closed，取消异常继续传播；只有平台 parser 返回 `INCOMPLETE_FIELDS`（已识别支付签名但字段不完整）时才执行一次截图/OCR；完整 parser 结果统一交给 `RecognitionCoordinator` 公有构造入口处理。Coordinator 的 `Saved`、`Duplicate`、`NeedsSelection`、`Rejected` 均为确定终态并 commit；capture/OCR/coordinator 异常、取消或临时失败 release，允许后续同页立即重试。
- 新增 `AccessibilityCaptureGateway`：`attach` 替换当前 provider，`detach` 后返回 `RequiresSystemConsent`，每个 `capture` 只调用 provider 一次，并发 capture 串行化；非取消 provider 异常脱敏为 `INTERNAL`。
- API 30+ 截图使用 `takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)`；`HardwareBuffer` 在 `finally` 中始终关闭，硬件 bitmap 复制为 `ARGB_8888` 软件 bitmap 并释放硬件包装；取消竞态通过 continuation 的取消回调回收返回 bitmap。
- 截图 provider 不信任 XML package filter 下缓存的前台包：请求前读取并释放 `rootInActiveWindow`，要求 cached/live 均为同一允许包后生成 reservation；callback 交付前再次读取 live root 并要求包名仍与 reservation、capture tag 一致。任一 null、非允许包或变化均 fail-closed，回收软件 bitmap 并返回脱敏 `INVALID_DISPLAY`。
- 自动 OCR fallback 对成功、前台包拒绝、OCR 异常、parser 拒绝、coordinator 异常和取消路径均在 `try/finally` 中回收 bitmap。
- 错误只映射为 `CaptureFailure`/`RecognitionDiagnostic` 枚举；没有保存页面原文或截图，也没有记录原始异常文本。

## TDD 证据

### RED

1. 首个有效功能 RED：focused accessibility unit test 编译仅因 `AutoPageRecognitionController`、`RecognitionDebouncer`、`AccessibilityCaptureGateway`/`CaptureResult` 尚不存在而失败。
2. 第二轮 unit RED：`AccessibilitySnapshotFactory` 与 framework screenshot error mapping 尚不存在。
3. 第二轮 androidTest RED：`PaymentPageAccessibilityService` 和 `R.xml.payment_page_accessibility_service` 尚不存在。
4. debounce mutation RED：临时移除 controller 的 debounce 拒绝分支后，`same normalized page inside debounce window is processed once` 以预期的 3 次处理对 1 次处理断言失败；恢复最小实现后转绿。
5. Fix round 1 RED：新增 live-window guard 与 `RecognitionDebouncer.reserve/commit/release` 测试后，编译按预期因 API 尚不存在失败。
6. Fix round 1 行为 RED：guard 对 capture tag 变化错误放行；临时 capture/coordinator/OCR 失败后的同页重试被旧 debounce 吞掉；确定的 OCR 方向冲突未提交 debounce。各测试均在对应最小修复前失败，修复后转绿。

首次构建曾在功能编译前因常见 Android SDK 路径不存在而失败；随后只读定位并复用 `E:\软件开发\DevTools\Android\sdk`。该环境失败未计为功能 RED。

### GREEN

- focused accessibility/capture JVM：35 个测试，0 failure。
- recognition 相关 JVM：`testDebugUnitTest --tests "com.local.autobookkeeping.recognition.*"`，通过。
- fresh full JVM：停止 daemon 后以 `--no-daemon --max-workers=1` 运行，290 tests / 0 failures / 0 errors / 28 suites。
- `compileDebugAndroidTestKotlin`：通过。
- `assembleDebug`：通过；生成 `app/build/outputs/apk/debug/app-debug.apk`。
- `git diff --check`：通过。

## 设备契约测试

严格只尝试一次、外层 60 秒超时：

```powershell
.\gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.local.autobookkeeping.recognition.accessibility.AccessibilityServiceContractTest --max-workers=1
```

初始实现阶段曾有一次 1.6 秒的无效 CLI 调用：PowerShell/Gradle 将未正确引用的 `-P...` 误解析为任务名，设备阶段未启动。随后任务协调者以正确参数执行了唯一一次有效 connected 尝试；该进程达到 60 秒硬超时后已终止，未继续轮询。Fix round 1 未重跑 connected test，未直接调用或重启 `adb`。因此不能声称设备契约通过；具体设备卡点为 instrumentation 在 60 秒内未完成。

## 静态契约核对

- merged manifest 中 service 使用且仅使用 `android.permission.BIND_ACCESSIBILITY_SERVICE` 作为该服务绑定权限，并包含正确 accessibility metadata。
- XML 的 event types、两个 package names、250 ms timeout、只读 flags、window content 和 screenshot 能力与任务简报一致；未声明 `canPerformGestures`。
- Task 8 新增源码未出现 `dispatchGesture`、`performAction`、原始日志或文件写入调用。
- instrumentation contract 源码已编译，覆盖 service declaration、包范围、截图/内容能力和无手势能力。

## 提交

- 提交信息：`feat: recognize WeChat and Alipay payment pages`
- 初始实现提交：`e224a296151b370b21e5fd46f525e329f951e132`
- Fix round 1：本报告更新所在提交；最终 SHA 由提交完成后的 `git rev-parse HEAD` 为准。
