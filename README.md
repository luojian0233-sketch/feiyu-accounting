# 自记账

自记账是一款 Android 8.0（API 26）及以上可用的离线个人记账应用。它支持手动收支、账本筛选、月度等区间统计、通知辅助记账、三套本地主题，以及通过系统文件选择器完成的本地 JSON 备份与恢复。

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

在“设置 → 外观”中可选择“清透薄荷”“小票账本”或“纸感极简”。选择后立即生效并保存在本机。

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

当前环境没有已连接的 Android 设备，且 Windows Hypervisor Platform 未启用，因此 `connectedDebugAndroidTest` 和[真机清单](docs/testing/manual-android-checklist.md)当前均为 **未验证**。端到端测试已经编译进 AndroidTest APK；这不代表它已在设备上通过。整体 MVP 尚未完成最终真机验收。

可行的无设备验证还会构建单元测试、lint、Debug APK 和 AndroidTest APK：

```powershell
./gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

## Debug APK

构建产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

本次 Task 10 可行验证产物为 `13,811,964` 字节，SHA-256：

```text
C97C7ED8249E8055BA16CD0F287EE2657334B4DD09784D331C9001892786FF92
```

连接已授权调试的 Android 8.0+ 设备后，可在项目根目录安装：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 是可重复生成的忽略构建产物，不提交到 Git；Task 10 报告同时记录了生成环境和 AndroidTest APK 证据。
