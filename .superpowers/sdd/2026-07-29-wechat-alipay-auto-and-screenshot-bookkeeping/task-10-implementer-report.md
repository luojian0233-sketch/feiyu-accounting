# Task 10 实施报告：单帧 MediaProjection 降级

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
