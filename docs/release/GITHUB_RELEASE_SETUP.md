# 用 GitHub Releases 发布更新

整套更新能力只依赖 GitHub 的公开接口，不花钱、不需要服务器：

```
App ──GET──> api.github.com/repos/<owner>/<repo>/releases/latest
                    │
                    └─ tag_name  → 版本号
                       body      → 更新日志
                       assets[]  → APK 下载地址
```

所以**没有 latest.json，也不需要另外维护一份元数据**。Tag 就是版本号，Release 正文就是更新日志，附件就是 APK。

---

## 一、先注册账号（如果还没有）

1. 打开 <https://github.com>，点 **Sign up**。
2. 邮箱 + 密码 + 用户名。用户名会出现在下载链接里，建议用英文/数字（例如 `feiyu`）。
3. 收验证邮件点确认。收不到就先去 Settings → Emails 重发。
4. 国内直连 github.com 一般能打开，只是偶尔慢；注册页加载不出来再考虑用手机热点。

## 二、建仓库

1. 右上角 **+ → New repository**。
2. Repository name 填 `feiyu-accounting`（随便，但建议固定下来）。
3. **Visibility 必须选 Public**。
   > 这是硬性要求：私有仓库的 Release 需要 token 才能读，而 App 里不存任何密钥。
   > 选 Private 的话，设置页会一直显示「检查失败」。
4. 下面的 README / .gitignore / license 全部**不勾**，本地已经有仓库了。

## 三、把本地代码推上去

本地代码在 `feature/android-mvp` 分支上，`master` 只有文档。先把代码并进 master：

```bash
cd "E:/软件开发/自动记账App"
git checkout master
git merge feature/android-mvp
git remote add origin https://github.com/<你的用户名>/feiyu-accounting.git
git push -u origin master
```

推的时候会弹出登录窗口，选 **Sign in with your browser** 就行——Edge 里已经登着账号，
点一下授权即可，凭据会存进 Windows 凭据管理器，以后不用再登。

> 如果没弹窗、而是直接问你要密码：GitHub 早已不支持账号密码，需要 Personal Access Token。
> GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) →
> Generate new token，勾 `repo`，把生成的 `ghp_...` 粘到密码位置。

> 不想动分支结构的话，也可以直接 `git push -u origin feature/android-mvp`，
> 然后在仓库 Settings → Branches 把默认分支改成它。

## 四、发第一个版本

### 1. 出包

```bash
cd "E:/软件开发/自动记账App/.worktrees/android-mvp"
# 先改 gradle.properties：appVersionCode=1、appVersionName=1.0
./gradlew :app:assembleRelease --offline
```

产物在 `app/build/outputs/apk/release/app-release.apk`。
它用的是 `C:/keys/feiyu-release.jks` 签名——**这个 keystore 丢了就永远无法覆盖升级**，记得另存一份。

> 离线打 release 时可能卡在 `lintVitalAnalyzeRelease`（该任务要单独下载 lint 工具）。
> 加上 `-x lintVitalAnalyzeRelease -x lintVitalRelease` 跳过它即可，单测已经覆盖了发布前要查的东西。

### 2. 在网页上建 Release

仓库页 → 右侧 **Releases → Create a new release**：

| 字段 | 填什么 |
|---|---|
| Choose a tag | 输入 `v1.0`，点 **Create new tag on publish** |
| Release title | `1.0` |
| Describe | 更新日志，客户端会原样读出来 |
| Attach binaries | 上传 APK，**文件名改成 `feiyu-1.0-vc1.apk`** |

文件名里的 `-vc1` 就是 versionCode，客户端优先用它判断新旧；没有这个后缀就退回按版本号比较。
两种都能用，但带上更严谨。

点 **Publish release**。

### 3. 告诉 App 仓库地址

`gradle.properties` 里填：

```properties
appUpdateRepo=<你的用户名>/feiyu-accounting
```

**必须重新构建安装一次**，因为它是编译期注入的。之后就固定不用再改了。

## 五、以后每次发版

1. `gradle.properties`：`appVersionCode` +1，`appVersionName` 改成新版本
2. `./gradlew :app:assembleRelease --offline`
3. 建 Release：tag = `v<版本名>`，APK 名 = `feiyu-<版本名>-vc<code>.apk`
4. 手机上：设置 → 版本 → 检查更新 → 下载并安装 → 等安装器弹出后覆盖安装

## 五之二、应用内直接更新（当前唯一的更新入口）

发现新版本后，版本卡片上除了「改用浏览器下载」还多一个「下载并安装」：

1. 点它，App 自己把 Release 里的 APK 下载到应用私有缓存（带百分比进度）；
2. 下载完立刻验包名、签名、versionCode，任一项不过就删掉缓存并红字说明；
3. 校验通过后直接调起系统安装器，不用离开 App、不用去浏览器。

第一次会跳到「安装未知应用」给肥鱼记账授权，之后就不再问了。
下载走的是同一个 GitHub 地址，所以国内网络该慢还是慢——慢就点下面的「改用浏览器下载」重试，
换 Wi-Fi 或换时间段都比反复点强。应用内下载偶尔失败不会留下半个包，下次点会重新下一份。

不再提供「从本地安装包更新」（选文件那条路）：它只解决「已把 APK 拷到手机」的场景，
和浏览器下载重叠，还多占一个系统文件选择器的入口。

## 六、几个必须注意的点

- **versionCode 只能加不能减**。手机已经装了 code=5，再发 code=4 的包会被安装器拒绝。
- **tag 别用 `latest` 这种滚动名字**，也不要用 `v1.0` 之外的格式发送；客户端会把 `v` 前缀去掉再比较。
- **匿名 API 限流是每小时 60 次/IP**。手动点按钮完全够，别做成定时轮询。
- **APK 传上去之后不要删**，老版本客户端就是靠它下载的。
- **不要「编辑」旧的 Release 来发新版**。改了旧 Release 的 tag 和附件，旧的发布页会一起消失
  （GitHub 只保留一条记录），老版本就再也下载不到了。每次发版都点 `Draft a new release`。
- 国内下载 GitHub 附件偶尔很慢或失败，这是 GitHub 的问题；设置页的「改用浏览器下载」走系统浏览器，可以重试或换网络。
