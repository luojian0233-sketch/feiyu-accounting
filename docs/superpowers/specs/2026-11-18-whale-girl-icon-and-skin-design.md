# 肥鱼记账 · 鲸鱼娘图标与深海皮肤（修订）

日期：2026-11-18
状态：设计（修订 2026-11-18 品牌方案里的「矢量小鱼 + 湖水青」视觉部分；品牌名「肥鱼记账」不变）

## 背景

上一版按「抽象胖鱼」做的矢量图标与湖水青皮肤，与用户心中「肥鱼」的实际所指不一致：用户指的是一位网络流行的鲸鱼娘形象（深蓝长发、白色女仆发带、鱼鳍耳、抱着饭碗）。用户提供了两张立绘作为参考，并要求界面元素可以自行创作，不必只在两张图上打转。

结论：**图标用她的形象，皮肤不用她的形象铺满**——图标承担「她是谁」，皮肤承担「她的颜色和气质」，界面装饰元素全部自绘。

## 图标（她本人）

- 素材：用户提供的第一张立绘（纯白底，384×384）。
- 处理链：白底泛洪抠图 → 连通域过滤（丢弃漂浮的金色星星等零散装饰）→ 底部 10% 柔化（避免裁切处出现硬直线）→ 按透明度加权重心居中 → 缩放贴合 72dp 安全圆。
- 裁切：头部 + 发带 + 鱼鳍耳 + 抱着的饭碗，保留「吃」的动作，这是她的识别点。
- 底色：竖向渐变 `#FFFBF2 → #FFE4BC`（暖奶白到暖沙）。选它是因为实测对比：青色或深蓝底会把深蓝头发吃掉、白围裙与白米饭也会和浅色底糊在一起；暖沙底上白帽、白饭、深蓝发三段同时立得住，48px 仍可辨认。
- 输出：
  - `mipmap-anydpi-v26/ic_launcher.xml`：`@drawable/ic_launcher_background` + `@mipmap/ic_launcher_foreground`。
  - `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png`：108dp 画布，角色缩放到 34.5dp 半径内（36dp 安全圆再留 4% 余量）。
  - `mipmap-{...}dpi/ic_launcher.png`：48dp 传统图标（无遮罩，角色占 44% 半径）。
  - 删除旧的 `drawable/ic_launcher_foreground.xml` 矢量与 `mipmap-anydpi/ic_launcher.xml` 回退（minSdk 26，自适应图标始终可用）。

## 皮肤（她的颜色，不是她的脸）

定位：深海与暖沙的对比，圆润、留白、零阴影；只在一处出现人物，其余交给自绘符号。

| 令牌 | 取值 | 说明 |
| --- | --- | --- |
| primary | `#2E5FAE` | 鲸蓝，按钮与选中态 |
| onPrimary | `#FFFFFF` | |
| primaryContainer | `#DCE8FB` | |
| onPrimaryContainer | `#12305E` | |
| secondary | `#4E8FD6` | 发梢湖蓝，鲸尾标记 |
| secondaryContainer | `#E4F0FD` | |
| tertiary | `#E8A93C` | 立绘里的金色闪光 |
| background | `#F6FAFF` | 海面反光 |
| onBackground / onSurface | `#0E1F33` | 深海墨 |
| surface | `#FFFFFF` | 卡片白（对应白围裙） |
| surfaceVariant | `#E6EFFA` | |
| outline | `#C3D6EC` | |
| shapes | 12 / 18 / 22 / 28 dp | |
| cardShape | `RoundedCornerShape(22.dp)` | |
| cardElevation | `0.dp` | 靠色阶分层 |
| separatorColor | `#DCE8F6` | |
| showFishMascot | `true` | 唯一切换皮肤行为的令牌 |

自绘元素（`ui/theme/WhaleElements.kt`，全 Canvas，无位图、无文字、无语义）：

- `WhaleWaveHeader`：首页顶部 104dp 深海横幅，`#1B3566 → #2E5FAE` 竖向渐变 + 三层正弦浪 + 三颗气泡 + 两颗金星；内含标题与头像插槽。
- `WhaleTailMark`：两瓣鲸尾（带中间凹口），用于「最近记录」小标题前缀。
- `SparkleMark`：四角星，供后续强调位复用。
- `WhaleMascot`：头部立绘贴在暖沙圆盘上，52dp，首页横幅右侧。
- `WhaleEmptyHint`：空记录时展示整身立绘 132dp + 引导文案（文案与其它皮肤一致，保证测试与无障碍路径不变）。

人物只出现在「今天」横幅与空状态两处；列表、表单、统计页保持纯排版。

## 兼容与不改动

- 备份仍按 `ThemeStyle` 名称校验，`FATTY_FISH` 枚举名与顺序不变。
- 默认主题仍是 `CLEAR_MINT`；其余三套主题的配色、形状、元素零改动（`showFishMascot = false` 时不渲染任何自绘元素）。
- 不改数据库、权限、通知识别、无障碍语义；装饰图 `contentDescription = null`。

## 验收

- `BrandIdentityContractTest`：`app_name` = 肥鱼记账；自适应图标引用新前景；五个密度同时存在 108dp 前景与 48dp 传统图；吉祥物位图 ≥ 256px；`WhaleElements.kt` 不含 `contentDescription = "..."`。
- `ThemeCatalogContractTest`：枚举顺序与 `FATTY_FISH` 备份值不变。
- JVM 全量测试通过；`assembleDebug` 成功；`aapt2 dump badging` 的 `application-label` 为「肥鱼记账」，图标指向 `mipmap-anydpi-v26/ic_launcher.xml`。
