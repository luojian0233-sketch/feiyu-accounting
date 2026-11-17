# 实施计划：鲸鱼娘图标与深海皮肤

对应设计：`docs/superpowers/specs/2026-11-18-whale-girl-icon-and-skin-design.md`

## Task 1 · 图标资源（她本人）

1. 用脚本完成抠图（白底泛洪 + 连通域过滤去星星）、底部柔化、重心居中。
2. 按密度导出 108dp 前景与 48dp 传统图到 `mipmap-{m,h,xh,xxh,xxxh}dpi/`。
3. `mipmap-anydpi-v26/ic_launcher.xml` 改为 `@mipmap/ic_launcher_foreground`。
4. `drawable/ic_launcher_background.xml` 改为 `#FFFBF2 → #FFE4BC` 竖向渐变向量。
5. 删除 `drawable/ic_launcher_foreground.xml` 与 `mipmap-anydpi/ic_launcher.xml`。

验收：`mipmap-xxxhdpi/ic_launcher_foreground.png` 为 432×432；圆形遮罩预览下头发、发带、饭碗均不被裁切。

## Task 2 · 皮肤令牌

1. `fattyFishColors` 换成鲸蓝系（primary `#2E5FAE`、secondary `#4E8FD6`、tertiary `#E8A93C`、background `#F6FAFF`）。
2. shapes 调为 12/18/22/28；`separatorColor` → `#DCE8F6`。

## Task 3 · 自绘元素与接入

1. 新增 `ui/theme/WhaleElements.kt`：`WhaleWaveHeader` / `WhaleTailMark` / `SparkleMark` / `WhaleMascot` / `WhaleEmptyHint`。
2. 删除 `ui/theme/FishMascot.kt`（旧矢量小鱼）。
3. `HomeScreen`：`showFishMascot` 为真时顶部换成深海横幅（标题 + 副标题 + 头像），「最近记录」加鲸尾前缀，空记录换成 `WhaleEmptyHint`；为假时保持原样。
4. 生成 `drawable-nodpi/whale_face.png`（288）与 `whale_mascot.png`（360）。

## Task 4 · 契约测试

1. `BrandIdentityContractTest` 重写图标断言：自适应 XML 引用、五密度位图尺寸（PNG IHDR 解析）、旧矢量已删除、吉祥物位图存在、装饰图无语义。
2. `ThemeCatalogContractTest` 保持不变，确认枚举顺序与备份值未动。

## Task 5 · 验证与提交

1. `:app:testDebugUnitTest`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`。
2. `aapt2 dump badging` 核验 label 与图标路径。
3. 更新 README 与 SDD 进度记录；分两次提交（资源+实现、文档）。
