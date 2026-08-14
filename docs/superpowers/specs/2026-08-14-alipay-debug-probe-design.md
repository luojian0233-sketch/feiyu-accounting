# 支付宝识别临时诊断探针设计

## 目标

在不输出或持久化支付宝页面文字、截图和账单详情的前提下，定位支付宝详情页未生成记账记录的具体阶段：无障碍截图、OCR/页面解析，或协调器保存。

## 方案

在 `app/src/debug` 增加一个临时、有序广播接收器。它只存在于 Debug APK，组件受系统 `android.permission.DUMP` 保护，因此仅允许 ADB shell 等受信任系统调用者显式调用。

接收器提供两个互斥模式：

1. `capture`：调用现有 `AccessibilityCaptureGateway`，只返回 `CAPTURE_SUCCESS`、`REQUIRES_CONSENT` 或规范化的 `CAPTURE_FAILURE_<TYPE>`；成功位图在返回前立即回收。
2. `process`：最多调用一次现有截图识别控制器，返回 `SAVED_PENDING`、`SAVED_CONFIRMED`、`DUPLICATE`、`NEEDS_SELECTION` 或 `REJECTED_<REASON>`。该模式沿用正式记账路径，识别成功时允许创建一笔待确认记录。

所有结果都通过有序广播的结果数据返回；不写日志、不写文件、不增加网络权限，不包含金额、商户、账户、OCR 文本、通知正文或图片数据。异步处理设 5 秒内部超时，任何异常归一为 `PROBE_INTERNAL_ERROR`。

## 验证流程

1. 编译并用 `adb install -r` 覆盖安装 Debug APK，确认既有 2 笔交易和系统授权保留。
2. 打开同一支付宝支付详情页，先运行 `capture`，记录规范化结果。
3. 仅当截图成功时运行一次 `process`，记录规范化结果并检查交易总数/待确认状态。
4. 根据首个失败边界确定根因，再为正式修复执行独立 TDD。
5. 定位完成后删除临时接收器及 Debug Manifest 声明；临时探针不进入最终产品提交。

## 成功标准

- 能唯一指出失败发生在截图、解析或保存哪个边界。
- 整个过程不输出或落盘任何原始支付数据。
- 不清除、不卸载应用，不运行 connected 测试；所有直接 ADB 命令保持 10 秒超时。
