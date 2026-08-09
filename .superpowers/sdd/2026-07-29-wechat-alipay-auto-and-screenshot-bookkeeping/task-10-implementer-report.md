# Task 10 实施报告：单帧 MediaProjection 降级

## 独立审查修复 Round 2

基于 Round 1 提交 `85d65077ccd58dd57e31d9c4246920becdaf5093`，修复两项 Important。

### RED

- 使用异步 fake 阻塞 callback 注册，并在注册返回前触发 `MediaProjection.Callback.onStop()`：旧实现会在资源句柄发布前清理，注册返回后的 callback 无人注销。
- 使用异步 fake 阻塞 VirtualDisplay 创建，并在创建返回前触发 stop：旧实现会在 VirtualDisplay 发布前清理，返回后的 display 无人释放。
- setup 抛错且已有 pending stop：新增断言确认 setup 完成前不得提前清理，完成后所有资源必须恰好清理一次且不得进入识别。
- 同一 service 实例收到重复命令：旧实现会对每条命令调用 `startForeground()`；同步失败后重复、成功后重复，以及“首条无效、后条有效”三种用例均暴露重复前台启动。

### GREEN

- `OneShotProjectionController` 使用 `setupInProgress` 与 `pendingSetupStop` 原子发布协议串行化 setup/stop；framework 调用期间不持锁，避免回调重入死锁。
- callback、listener 与 VirtualDisplay 的每个 setup 边界后都重新检查 pending stop；setup 的 `finally` 在句柄发布完成后统一触发幂等 cleanup。
- Android projection callback 句柄在调用 framework 注册前先发布；VirtualDisplay 在 framework 返回后、控制器继续前发布。异步 stop、setup exception 和重复 stop 下每项资源均恰好清理一次，且不进入 processor。
- `ProjectionServiceStartCoordinator` 先用 CAS 原子认领首条命令，再执行 `startForeground()`、capture 或 finish；后续重复命令完全忽略。首条无 token 时仍先进入前台并 finish 一次，后续有效命令仍忽略；返回值保持 `START_NOT_STICKY`。

### Round 2 验证

- Focused projection/tile/gateway/recognition：`BUILD SUCCESSFUL`。
- Fresh full JVM：`355` tests，`0` failures。
- `compileDebugAndroidTestKotlin`：`BUILD SUCCESSFUL`。
- `assembleDebug`：`BUILD SUCCESSFUL`，debug APK 存在。
- 所有 Gradle 命令均使用 `--max-workers=1` 和显式超时；未运行 connected、adb，未创建或修改 `local.properties`。

## 范围与基线

- BASE：`0a5b7d56084c3b4ed9393839a157e82d5ad4ef10`
- 只实施 Task 10；未开始 Task 11 及后续任务。
- 未创建或修改 `local.properties`；构建仅设置进程级 `JAVA_HOME`、`ANDROID_HOME` 与 `ANDROID_SDK_ROOT`。
- 未运行 `connectedDebugAndroidTest`，未调用 `adb`。

## TDD 记录

### RED

1. `OneShotProjectionControllerTest` 首次运行在测试编译期失败，缺少 `OneShotProjectionController`、projection resource/frame/timeout 抽象。
2. `compileDebugAndroidTestKotlin` 首次运行失败，缺少 `ProjectionCaptureService`。
3. timeout 发生在挂起的识别处理期间时，新测试证明 Image/bitmap 未立即释放；根因是控制器未持有并取消 processing job。
4. 非零 plane buffer position 的 RGBA 测试失败，证明复制逻辑错误地从 buffer 下标 0 开始。
5. gateway 安全分类测试共 7 项失败，证明 stale/replay/expired/reservation 失败/detach/代际变化被错误折叠为 `RequiresSystemConsent`。

### GREEN

- 一个 projection grant 只打开一次资源并创建一个 VirtualDisplay。
- Android 14 所需的 `MediaProjection.Callback` 在 VirtualDisplay 创建前注册。
- 原子认领首帧；后续 Image 只关闭、不识别。
- success、setup failure、copy failure、processor failure、timeout、projection stop、listener error、取消均释放 Image、ImageReader、VirtualDisplay、MediaProjection、callback、HandlerThread、bitmap 与 timeout。
- timeout 会取消挂起的 processing job；`ScreenshotRecognitionController` 已回收 bitmap 时控制器幂等跳过，避免二次回收错误。
- RGBA 复制尊重 buffer position、pixelStride、rowStride，并裁剪为真实屏幕宽高的 `ARGB_8888` 软件 bitmap。
- 只有 `RequiresSystemConsent` 和明确的 `NO_ACCESSIBILITY_ACCESS` 进入每次系统授权；secure window、invalid display、限流和内部错误 fail-closed。
- gateway 仅在 manual session 创建时没有 accessibility provider 才返回 `RequiresSystemConsent`；stale/replay/expired/reservation 失败/detach/代际变化返回 `INVALID_DISPLAY`。
- 授权拒绝、取消或缺少 result Intent 只记录脱敏诊断并 finish，不启动 projection、OCR 或持久化。
- 前台服务不导出，`START_NOT_STICKY`；无 token 重启只启动私有通知后立即移除并停止，不做 capture。
- 通知为 local-only、private、ongoing，Android 12+ 请求 immediate foreground behavior；停止时移除通知并 `stopSelf()`。

## Android 配置

- 新增 `android.permission.FOREGROUND_SERVICE`。
- 新增 `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`。
- `ProjectionCaptureService`：`exported=false`、`foregroundServiceType=mediaProjection`。
- 合并后的 debug manifest 已核对上述权限、私有 service 与 service type。

## 验证结果

环境公共前缀：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='E:\软件开发\DevTools\Android\sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
```

1. Focused tile/projection/recognition/gateway：成功。

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGatewayTest" --tests "com.local.autobookkeeping.recognition.capture.AccessibilityCaptureGatewayGenerationTest" --tests "com.local.autobookkeeping.recognition.capture.ScreenshotRecognitionControllerTest" --tests "com.local.autobookkeeping.recognition.tile.*" --max-workers=1
```

2. Fresh 全量 JVM：成功，`343` tests，`0` failures。

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --max-workers=1
```

3. Android instrumentation 源码与 manifest contract 编译：成功。

4. Debug APK 组装：成功，产物 `app/build/outputs/apk/debug/app-debug.apk` 存在。

```powershell
.\gradlew.bat compileDebugAndroidTestKotlin assembleDebug --rerun-tasks --max-workers=1
```

AndroidTest 编译仍显示仓库既有 Compose test v1 deprecation 警告；APK 组装仍显示既有 native library 无法 strip 的提示。两者均非本任务新增，命令最终为 `BUILD SUCCESSFUL`。

## 独立审查修复 Round 1

基于提交 `13fb64fdfbe3a738956a20bbd13516f577fc4ec3` 修复一项 Critical 和一项 Important。

### RED

- Manifest 语义测试证明 `ScreenshotCaptureActivity` 的 `noHistory=true` 与 `startActivityForResult/onActivityResult` 授权流程冲突。
- 阻塞 `copyToSoftwareBitmap()` 时触发 projection stop，旧控制器会并发关闭 ImageReader、VirtualDisplay、MediaProjection 与 HandlerThread。
- 等待首帧 timeout 先赢后，late frame 没有被 acquire/close。
- 首帧先赢后，等待首帧 timeout 没有在 copy 前立即取消。
- parser 返回候选后取消 coroutine，旧 recognition controller 仍会进入 coordinator 并产生一次 store write。
- stop 与 resource factory open 并发时，旧 cleanup 标记会导致稍后返回的 projection resources 泄漏。

### GREEN

- 移除 capture activity 的 `noHistory`；增加 source manifest 与 instrumentation PackageManager 双重 contract。授权 success、denial、missing result data 均走单次路由并安全 finish。
- `OneShotProjectionController` 改为线性化状态：`NEW → WAITING → COPYING → PROCESSING → FINISHED`，另有 copy/processing stop 状态。
- timeout 只竞争 `WAITING`；首帧 CAS 获胜即取消 timeout，timeout 获胜后的 late frame 只 close、不 process。
- Image 所有权期间同步完成软件 bitmap copy；copy 期间 stop 只记录状态，不关闭 reader/plane。copy 返回后先 close Image 和全部 capture resources，再决定回收 bitmap 或开始识别。
- processing stop 只协作取消 coroutine；不并发回收 processor 正在使用的软件 bitmap，识别完成或响应取消后才结束 service。
- `ScreenshotRecognitionController` 在调用 `RecognitionCoordinator` 前执行 `currentCoroutineContext().ensureActive()`；取消用例确认 `0` 次持久化。
- setup error、同步 `MediaProjection.Callback.onStop()`、第二帧、重复 stop/finish 和 factory-open 竞态均验证幂等清理。

### Round 1 验证

- Focused projection/tile/gateway/recognition：`BUILD SUCCESSFUL`。
- Fresh full JVM：`350` tests，`0` failures。
- `compileDebugAndroidTestKotlin`：`BUILD SUCCESSFUL`。
- `assembleDebug`：`BUILD SUCCESSFUL`，debug APK 存在。
- 所有 Gradle 命令均使用 `--max-workers=1` 和明确超时；未运行 connected、adb，未创建 `local.properties`。

## Round 3 independent-review repair

### Regression coverage and production change

- Added focused `OneShotProjectionControllerTest` coverage for stop-before-start, blocking factory/callback/VirtualDisplay setup, setup-pending first-frame handoff, pending stop, second-frame closure, and setup failure.
- `OneShotProjectionController` now atomically claims `NEW -> SETTING_UP` before any factory call. `stop()` moves setup only to `SETUP_STOP_PENDING`; setup publishes or finishes its active framework call, then closes the pending frame and every published resource exactly once.
- A frame arriving during setup is retained only if it is the first one; later frames close immediately. Once setup reaches `WAITING`, the retained frame is handed off through the normal copy path. If a stop is pending, it is closed without copying.
- Removed the unused `kotlinx.coroutines.delay` import from `ScreenshotCaptureActivity`.

### Verification status

- Static check: `git diff --check` completed with exit code 0. The controller was also manually checked for every `CaptureState` branch and for the setup-to-waiting/frame handoff race.
- Dynamic Round 3 verification was **not run to completion**. In this managed sandbox Gradle fails during configuration because Java ZipFS cannot close a Gradle distribution JAR (`java.nio.file.AccessDeniedException`); an earlier wrapper attempt also failed while downloading with `java.net.SocketException: Permission denied: getsockopt`. No Gradle command reached test execution, so this report does not claim GREEN.
- Round 2's historical baseline reported 355 JVM tests, `compileDebugAndroidTestKotlin`, and `assembleDebug` as successful. Those results predate Round 3 and do not verify these changes.
