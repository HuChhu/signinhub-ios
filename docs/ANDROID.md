# Android APK 构建与使用

## 产物

```
signin-hub/SigninHub-1.6.apk      20.09 MB   debug 签名
```

包名 `com.signhub.app`，minSdk 26（Android 8.0+），targetSdk 34。

### 版本记录

| 版本 | 变更 |
|---|---|
| 1.0 | 原生 Compose 界面 + 本地签到 |
| 1.1 | 界面改成内置网页（WebView），与 PWA 同一套 UI |
| 1.2 | 修复页面缩成窄条（`useWideViewPort`） |
| 1.3 | 修复豆包没有真正入口；隐藏无用的「连接电脑端」 |
| 1.4 | 输入框提示改成手机端口径 |
| 1.5 | 修复 `Failed to fetch`（fetch 劫持改用 documentStart 注入） |
| **1.6** | **加入后台自动签到（WorkManager）+ 结果通知 + 一键带豆包** |
| **1.7** | **豆包签到下线（2026-11-20 平台风控 710010702）**：移除首页「豆包签到」按钮、无障碍自动点击入口、查询/领取按钮与相关提示；`Bridge.openDoubao()` 改为空实现，`/api/doubao_*` 只返回 `retired` |

## 界面 = 网页版

APK 里的界面不是另写的原生 UI，而是把 `backend/web/` 整套打包进 `assets/`，
用全屏 WebView 加载，所以和手机浏览器打开网页版**完全一致**。

```
assets/index.html + static/*        ← 从 backend/web 原样复制
        ↓  WebViewAssetLoader
https://appassets.androidplatform.net/index.html
        ↓  documentStart 注入 fetch shim（页面 JS 零改动）
fetch("/api/status")  →  SignHubApi.request()  →  LocalApi.kt
```

`LocalApi.kt` 实现了网页版依赖的全部 `/api/*` 接口
（status / logs / stats / config / doctor / checkin / checkin/all / test /
test_notify / trae_probe / wb_accounts / growth / heat / wb_assets / refresh_totals；
豆包的 `doubao_probe` / `doubao_login` / `doubao_tasks` / `doubao_quota`
已在 V2.25 清理时连同实现一起删除，现为 404）。

## 自动签到（1.6 新增，核心功能）

**之前只有「提醒」，不会真的签到。** 现在：

- `AutoCheckinWorker`：后台真正执行 WorkBuddy + Trae 的 HTTP 签到，写记录，发结果通知
- `AutoCheckinScheduler`：WorkManager 每日定时

设计取舍：

| 选择 | 原因 |
|---|---|
| 用 WorkManager 而非 AlarmManager | BroadcastReceiver 只有约 10 秒，两个网络签到容易超时；精确闹钟还要 Android 12+ 的 `SCHEDULE_EXACT_ALARM` 权限 |
| 用「单次任务 + 自我重排」而非 `PeriodicWorkRequest` | Periodic 周期固定 24 小时，某次被 Doze 延后 2 小时，**之后每天都在那个延后时刻跑**（漂移）。单次模式每次按「下一个 08:30」重算，不累积误差 |

时间计算已验证：

```
今天 07:00 -> 等 1h30m
今天 08:29 -> 等 1m
正好 08:30 -> 等 24h（次日）
今天 21:00 -> 等 11h30m
今天 23:59 -> 等 8h31m
```

**豆包已不在签到范围内（2026-11-20 下线）**：服务端对签到写接口固定返回 `710010702`，官方客户端
手动点也失败，属平台侧风控；后台 Worker 的通知与统计里已移除豆包，历史记录仍保留。

### 必须在手机上做的两件事

1. **允许通知**（Android 13+ 首次启动会弹窗）—— 否则签到成功也没有提示
2. **关闭电池优化** —— 设置页「高级」→「关闭电池优化（保后台）」
   国产 ROM 的省电策略会杀后台，导致 WorkManager 不跑

设置页「高级」里还有「**立即测试后台签到**」，走的是和定时完全相同的代码路径，
可以马上验证后台链路是否通（完成后会收到通知）。

## 已装好的工具链（都在 D 盘）

| 组件 | 位置 | 版本 |
|---|---|---|
| JDK | `D:\devkit\jdk-17.0.21+9` | 17.0.21 |
| Gradle | `D:\devkit\gradle-8.9` | 8.9 |
| Android SDK | `D:\Android\Sdk` | platform-34 / build-tools 34.0.0 |

## 重新构建

**改了网页一定要先同步 assets**，否则 APK 里还是旧页面：

```powershell
$root = "<项目>\signin-hub"
Copy-Item "$root\backend\web\index.html"           "$root\android\app\src\main\assets\" -Force
Copy-Item "$root\backend\web\manifest.webmanifest" "$root\android\app\src\main\assets\" -Force
Copy-Item "$root\backend\web\sw.js"                "$root\android\app\src\main\assets\" -Force
Copy-Item "$root\backend\web\static"               "$root\android\app\src\main\assets\static" -Recurse -Force

$env:JAVA_HOME = "D:\devkit\jdk-17.0.21+9"
D:\devkit\gradle-8.9\bin\gradle.bat -p "$root\android" assembleDebug
```

产物：`android\app\build\outputs\apk\debug\app-debug.apk`

## 安装

```powershell
D:\Android\Sdk\platform-tools\adb.exe install -r SigninHub-1.6.apk
```

或直接传到手机点开安装（允许「未知来源」）。

## 填凭据（App 设置页）

| 服务 | 字段 | 从哪拿 |
|---|---|---|
| **WorkBuddy** | accessToken、uid、domain | 电脑 `~/.dsh/workbuddy-vault/cn/*.json` 的 `auth.accessToken` / `account.uid` / `auth.domain`（domain 是 `www.workbuddy.cn`） |
| **Trae Solo** | Cloud-IDE-Token、x-device-id | `www.trae.cn` 登录后 Console 执行 `localStorage.getItem('Cloud-IDE-Token')`；device-id 是 16 位数字 |
| ~~豆包~~ | — | **已下线（2026-11-20）**：服务端风控 710010702，无需任何凭据 |

现成值见项目根目录 `credentials-to-fill.txt`（V2.25 清理磁盘缓存时已移除；现在直接点 App 里的「登录主账号」自动获取，不必手抄）。

## 代码结构

| 文件 | 作用 |
|---|---|
| `WebUiActivity.kt` | 主界面：WebView + AssetLoader + fetch shim + 原生桥 |
| `LocalApi.kt` | 本地实现全部 `/api/*`，返回结构与 Python 后端一致 |
| `AutoCheckinWorker.kt` | 后台签到任务 + 结果通知 |
| `AutoCheckinScheduler.kt` | WorkManager 定时调度 |
| `HistoryStore.kt` | SQLite 记录（同 provider+day 只留一条，覆盖不堆叠） |
| `LocalCheckin.kt` | WB / Trae 纯 HTTP 签到（OkHttp） |
| `DoubaoWebActivity.kt` | ⚠ 已下线，保留未调用：豆包 WebView 签到（UA 伪装 `SamanthaDoubao` + JS 注入点击） |
| `DoubaoTaskStore.kt` | ⚠ 已下线，保留未调用：豆包任务结果缓存 |
| `AutoClickService.kt` | ⚠ 已下线，组件保留未启用：原用于豆包无障碍自动点击 |
| `Settings.kt` | 凭据存储（DataStore） |
| `BootReceiver.kt` | 开机后恢复定时 |
| `MainActivity.kt` | 旧的原生 Compose 界面，保留但不在桌面显示 |

## 踩过的坑（都修了，改代码时别踩回去）

### 0. 点按钮报 `Failed to fetch`（1.5 修复）

**现象**：点「登录 Trae 账号」「测试签到」等按钮，弹 `Failed to fetch`。

**原因**：fetch 劫持脚本没生效，请求跑到真实网络 ——
`https://appassets.androidplatform.net/api/...` 这个域名不存在，DNS 失败就是 Failed to fetch。

为什么没生效：早期用 `onPageFinished` + `evaluateJavascript` 注入，两个问题：

1. **时机太晚** —— `app.js` 已经执行完，首屏请求可能已发出
2. **脚本含中文** —— 注入的 JS 里有中文注释，部分 WebView 会静默失败

**修复**：

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
    WebViewCompat.addDocumentStartJavaScript(
        web, coreShim, setOf("https://appassets.androidplatform.net"))
}
```

三条规矩：

- 核心 shim 必须**纯 ASCII**（实测 0 个非 ASCII），中文提示挪到 `onPageFinished` 的 UI 补丁
- 从 `.kt` 里提取注入的 JS，用 `node --check` 验语法
- 兜底：`shouldInterceptRequest` 也拦 `/api/`，shim 没跑起来时 GET 仍走本地

### 1. 页面缩成中间一条（1.2 修复）

**现象**：界面只占屏幕约 1/3，左右大片黑边。

**原因**：WebView 默认 `useWideViewPort = false`，会忽略 `index.html` 里的
`<meta viewport width=device-width>`，按 980px 桌面宽度排版；CSS 里 `.app{max-width:460px}`，
于是 460/980 ≈ 47%，缩成窄条。

**修复**：

```kotlin
useWideViewPort = true       // 必须！否则 meta viewport 不生效
loadWithOverviewMode = true
// 不要调 setInitialScale()，它会覆盖基于 meta viewport 的自动适配
```

### 2. Kotlin 嵌套块注释把整个文件吞了

**现象**：`Unclosed comment`，报错行号在文件末尾。

**原因**：Kotlin 支持嵌套块注释。KDoc 里写 `把 /api/* 拦下来`，其中的 `/*`
被当成新的块注释开头，需要额外 `*/` 闭合，于是后面全被吃掉。

**规矩**：注释里不要写 `/api/*`，改成 `/api 请求`。

### 3. assets 必须是根布局

`index.html` 用的是绝对路径（`/static/app.js`、`/static/logos/*`）。
放在 `assets/web/` 下会导致所有 CSS/JS/LOGO 404。

正确布局：

```
assets/index.html
assets/manifest.webmanifest
assets/sw.js
assets/static/app.css
assets/static/app.js
assets/static/logos/*.png
```

配合 `addPathHandler("/", AssetsPathHandler(this))` 做根映射。

### 4. `AssetsPathHandler` 只有一个构造函数

只有 `AssetsPathHandler(Context)`，没有 `(Context, String)` 重载。

### 5. 别用 PowerShell 的 Get-Content/Set-Content 改带中文的文件

`Get-Content` 默认按 GBK 读 UTF-8 文件，`-replace ... | Set-Content` 会把中文写成乱码。
改文档用编辑工具，或显式指定 `-Encoding UTF8` 且确认读写一致。

## Trae 没有扫码登录

实测 `www.trae.cn/login` 只有：

```
手机号 + 短信验证码   ← 最快
抖音登录
苹果登录
企业账号登录
```

**没有二维码。** 所以：

- APK 里点「登录 Trae 账号」不会有二维码 —— 1.5 起改成提示「请到电脑复制 Token 粘贴」
- 电脑端脚本打开的 login 页，如果已登录过会自动跳回首页，看不到表单是正常的

## 已知未验证项

没有 Android 设备连到构建机（`adb devices` 为空）。已确认：

- ✅ 编译通过、APK 可安装、清单正确
- ✅ 内置网页渲染验证通过（标题、进度环、三个 LOGO、记录页，控制台零错误）
- ✅ 签到参数用 Python 模拟验证过（WorkBuddy `code:0 streak:20`，Trae `code:0 credits:100`）
- ✅ 用户真机确认：**一键签到后三个服务实际都签上了**
- ⬜ 后台自动签到（WorkManager）尚未在真机跑过完整周期

真机重点看：

1. 设置页「高级」→「立即测试后台签到」，几分钟内应收到结果通知
2. 如果通知没来，检查是否允许了通知权限、是否关掉电池优化
3. Trae 报 `code=9074` → device-id 不是 16 位数字；报 `401` → token 过期（8 小时）