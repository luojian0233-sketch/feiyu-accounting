# 肥鱼记账

肥鱼记账是一款 Android 8.0（API 26）及以上可用的离线个人记账应用。它支持手动收支、账本筛选、月度等区间统计、通知辅助记账、四套本地主题，以及通过系统文件选择器完成的本地 JSON 备份与恢复。

## 隐私与权限

- 账本、分类、账户、商户规则和设置仅保存在设备本地；应用不提供云同步，也不需要网络权限。
- 应用不申请广泛存储权限。备份导入、导出使用 Android 系统文件选择器（SAF），文件位置由用户选择。
- 自动记账依赖 Android 的“通知使用权”。首次使用时可在首页查看未开启说明，再通过“设置 → 通知读取权限 → 打开系统通知使用权设置”授权。
- 只有在应用内启用的微信、支付宝或精确匹配白名单的银行应用来源才会被处理。关闭来源开关或撤销通知使用权后，不会继续读取该来源的新通知。
- 通知格式由支付应用控制，版本或文案变化可能导致无法识别。信息不完整、方向不明确、转账类或低置信度候选必须进入“待确认”；无法安全解析的格式应留下诊断或被忽略，绝不能静默生成一笔已确认交易。请定期核对账本。
- 通知辅助记账不等同于银行对账，且可能受系统省电策略、进程状态、通知聚合方式和设备厂商限制影响。

## 记账与统计

手动记录可以选择支出或收入、分类、账户、日期和备注。启用“内部转账”后，记录仍保留在账本中，但不计入收入、支出、余额或分类统计，避免账户间转移被重复计算。

统计支持日、周、月、年和自选区间，并按支出或收入分别汇总。待确认记录和内部转账不会进入已确认的收支统计。

## 主题

在“设置 → 外观”中可选择“清透薄荷”“小票账本”“纸感极简”或“肥鱼简约”。选择后立即生效并保存在本机。“肥鱼简约”以湖水青与浅水色为底、大圆角卡片替代阴影分层，并在首页标题行右侧显示一条小肥鱼。默认主题仍是“清透薄荷”，已有偏好不受影响。

## 本地备份与恢复

1. 打开“设置 → 本地备份”。
2. 选择“导出备份”，在系统文件选择器中确定 JSON 文件位置。
3. 恢复时选择“导入备份”并选取有效 JSON 文件。
4. 阅读替换提示，确认后才会用备份中的交易、分类、账户、商户规则和主题替换当前数据。
5. 损坏、格式错误或版本不受支持的文件必须被拒绝，当前数据应保持不变。重要账本请保留多份离线副本，并先在可控设备上验证恢复。

## 构建与验证

完整的 Task 10 验证命令是：

```powershell
./gradlew.bat clean :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
```

2026-07-27 已在真机 PKG110（Android 16 / API 36）安装干净构建的 Debug APK 和 AndroidTest APK，并运行完整 instrumentation 测试集：`23 tests, 0 failed, 0 ignored`。其中 `EndToEndTest.completePersonalLedgerJourney` 已验证手动支出、手动收入、内部转账、统计排除、主题切换与账单持久化旅程。

由于所有直接 `adb` 调用按本次验收要求限制为 10 秒，设备端测试使用带 60 秒硬上限的 AndroidJUnitRunner 执行并保存完整结果，而不是让 Gradle 的 `connectedDebugAndroidTest` 无期限等待。人工[真机清单](docs/testing/manual-android-checklist.md)尚未逐项执行，因此整体 MVP 仍不能标记为全面人工验收通过。

可行的无设备验证还会构建单元测试、lint、Debug APK 和 AndroidTest APK：

```powershell
./gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

## Debug APK

构建产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

本次真机验证使用的干净构建产物为 `13,811,964` 字节，SHA-256：

```text
469F6A270A0DA13B373BC69528A6BFDF7DA9EA68F5A980F4312D6D77B18FBA91
```

连接已授权调试的 Android 8.0+ 设备后，可在项目根目录安装：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 是可重复生成的忽略构建产物，不提交到 Git。本次同时验证了 169 个 JVM 测试（0 失败）、Lint（0 错误、5 个警告）以及 23 个真机 instrumentation 测试（0 失败）。

## 肥鱼记账品牌构建（2026-11-18）

改名与图标改造后的干净构建：`app-debug.apk` 为 `59,681,609` 字节，SHA-256：

```text
6B1E8F0EE1A7AF430AF62D49ED0627E6366F61DDF2BCC5A6BE8CA8031B869D8E
```

- JVM：49 个 suite、450 个测试，0 失败、0 错误、0 忽略（含 `BrandIdentityContractTest` 与 `ThemeCatalogContractTest`）。
- `assembleDebug` 与 `compileDebugAndroidTestKotlin` 通过；`aapt2 dump badging` 显示 `application-label:'肥鱼记账'`，图标指向 `res/mipmap-anydpi-v26/ic_launcher.xml`。
- 本机网络无法访问 Google Maven，`lintDebug` 未能执行（`androidLintTool` 依赖无法下载），本轮没有 lint 结论。
- 未连接真机：桌面名称、图标外观与“肥鱼简约”皮肤的目视验收仍为 `未验证`。

