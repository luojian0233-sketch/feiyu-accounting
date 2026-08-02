# Task 9 实现报告：快捷设置截屏记账

## 范围与基线

- 基线：`ebdc4733503a85eea362073bac1f1e9582e1c29d`
- 工作区：`R:\`
- 仅实现 Task 9；未实现 Task 10 的 MediaProjection 或后续 UI。
- 未创建 `local.properties`；构建只设置进程级 `JAVA_HOME`、`ANDROID_HOME` 与 `ANDROID_SDK_ROOT`。
- 按协调要求未运行 `connectedDebugAndroidTest`、未直接调用 `adb`，设备验收保留给 Task 14。

## 实现摘要

- 新增 `ScreenshotRecognitionController`：在覆盖整个 OCR、parser 和 coordinator 流程的 `try/finally` 中回收 bitmap；OCR 失败脱敏为 `OCR_FAILED`，取消继续传播。OCR 的 `ObservedTextElement` 原样写入 `PageSnapshot`，保留边界几何、观测时间与经过证明的包名。
- 微信与支付宝精确包名分别使用平台 parser；其他包和 `null` 使用 `GenericBillParser`。`Single` 只交给 `RecognitionCoordinator.process()`；`Multiple` 直接构造 `NeedsSelection`，不写库；`Rejected` 原样映射 reason。
- 扩展 `AccessibilityCaptureGateway`：保留 Task 8 自动 `capture()` 入口和串行化语义，另增独立的手动 provider 与 2 秒、单活动、一次性 token。自动 capture 不能消费手动 token；token 重放、过期或 detach 后均 fail-closed 为 `RequiresSystemConsent`。
- Task 8 自动截图仍使用原 `ActiveWindowPackageGuard` 的请求前 cached/live 相等校验和 callback 交付前 live 二次校验，没有放宽允许包或形成 stale-package 绕过。
- 手动截图由独立 `ManualCaptureWindowGuard` 保护：capture 前后均核对 active root、可见外部窗口集合与 capture tag。SystemUI 仍为 active 时拒绝；透明 activity 为 active 时，仅唯一且前后不变的外部窗口可归因包名。无法唯一证明时包名为 `null`，因此 controller 使用 Generic/OTHER；不会采用缓存的微信/支付宝包名进行猜测。proof 变化时立即回收软件 bitmap 并返回 `INVALID_DISPLAY`。
- 新增透明、无动画且不导出的 `ScreenshotCaptureActivity`。`onResume` 经可注入的 350 ms 面板收起延迟后只 capture 一次；成功、parser/coordinator outcome、capture failure、权限缺失和非取消异常均在回收 bitmap 后安全 finish。取消继续传播，但仍执行 finally 清理。
- 新增 `ScreenshotBookkeepingTileService`：API 34+ 使用 immutable/update-current `PendingIntent` 的 `startActivityAndCollapse`；旧版使用 Intent 路径。TileService 只能由 `BIND_QUICK_SETTINGS_TILE` 绑定，并使用真实 UTF-8 中文 label“截屏记账”和自定义 vector icon。
- 新增 `QuickSettingsTileLauncher`：API 33+ 调用 `StatusBarManager.requestAddTileService`；added、already-added、not-added 与未知状态分别映射为 `Requested`、`AlreadyAdded`、`Failed`、`Failed`，系统服务不可用或抛异常时 fail-safe；API 32 及以下返回 `ManualInstructions`。
- `ApplicationGraph` 提供单例 gateway 和 screenshot controller；accessibility service attach 自动与手动两个 provider，销毁时统一 detach。没有保存截图、OCR 聚合文本、页面原文或原始异常日志。

## TDD 证据

### RED

1. 首轮 focused test 在功能编译阶段按预期失败：缺少 `ScreenshotRecognitionController`、manual gateway session API、`ManualCaptureWindowGuard`、activity runner、tile launcher/service/activity 与结果类型。首次命令曾先因未设置 JDK 失败；该环境失败未计为功能 RED，定位既有 JDK/SDK 后重跑获得有效 RED。
2. instrumentation 契约在资源实现前编译失败，明确缺少中文 label、vector icon 与透明主题资源。
3. 初次 focused GREEN 尝试运行 30 tests，其中 27 通过；3 个 manual-session 测试因默认时钟调用 Android stub 在纯 JVM 抛异常。改用仍可注入的 JVM 单调时钟后全绿。
4. 安全结束补强 RED：`processor failure is redacted after recycling bitmap and finishing activity` 在最小修复前以原始 `IllegalStateException` 失败；增加 activity 边界脱敏后转绿。

### GREEN

- focused Task 9、gateway 与 manual guard：30 tests，0 failure。
- recognition 全包 JVM：通过，包含 Task 8 自动识别、capture、debounce、live-window guard、parsers 与 coordinator 回归。
- fresh full JVM：停止 daemon 后以 `--no-daemon --max-workers=1` 运行，32 suites / 315 tests / 0 failures / 0 errors / 0 skipped。
- `compileDebugAndroidTestKotlin --max-workers=1`：通过。
- `assembleDebug --no-daemon --max-workers=1`：通过；APK 为 `app/build/outputs/apk/debug/app-debug.apk`。
- `git diff --check`：通过。

## 设备测试说明

未运行 `connectedDebugAndroidTest`。Task 8 已记录设备 Gradle 在 60 秒内无输出并被终止；本任务遵循协调要求只编译 `ScreenshotTileContractTest`，不再次占用设备阶段，不直接调用 `adb`。

## 提交

- 提交信息：`feat: add Quick Settings screenshot bookkeeping`
- 最终 SHA：提交后以 `git rev-parse HEAD` 为准。
