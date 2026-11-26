# 豆包每日签到（已下线 · 2026-11-20，V2.25 已彻底移除）

> **状态：代码已全部删除。**
> 豆包服务端现在对签到写接口固定返回 `710010702` 系统错误，**连官方客户端手动点签到也一样失败**。
> 凭据、UA、DPAPI 解密、Playwright 点击链路全部验证正常（28 个 cookie 齐全、`key_ok=true`），
> 失败点在服务端写接口 → 属平台侧风控封禁，客户端没有任何修复空间，重试无意义。
>
> 下线分两步：
> 1. **2026-11-20 摘入口**：前端面板与按钮、`/api/status` / `/api/doctor` 与「一键全部签到」都不再带它。
> 2. **2026-11-21 V2.25 彻底删除**（用户 m17251「清理未用代码」）：
>    - 后端：删 `backend/signbot/doubao.py` 整文件；`provider.py` 去掉 `DoubaoProvider` / `_run_doubao()` / `PROVIDER_CLASSES["doubao"]` / `readiness()` 的 doubao 分支；`config.py` 去掉 doubao 默认配置块；`server.py` 删掉 5 个 `/api/doubao_*` 路由（现在访问返 404，不再是 `{"retired":true}`）。
>    - 前端：`app.js` 的 `LABELS` / `LOGOS` / `LOGO_2X` / `EMOJI` / `BADGES` / 记录页筛选 chip / `mode === "doubao"` 判定全部去掉豆包条目；`index.html` 的 `#dbPanel` 空壳 section 与「豆包」筛选 chip 一并删除。
>    - Android：删 `DoubaoWebActivity.kt`、`DoubaoTaskStore.kt`；`LocalApi` 删 4 条 `/api/doubao_*` 路由与 `recordDoubao()`；`WebUiActivity` 删 `doubaoLauncher` / `dbTaskLauncher` / `Bridge.openDoubao()` / `openDoubaoTasks()` / `launchDoubaoApp()` / `openDoubaoApp()` 与注入 JS 的帮助块；`AutoCheckinWorker` / `MainActivity` / `MainViewModel` / `ReminderReceiver` 的豆包分支与文案清掉；`AndroidManifest.xml` 去掉 `.DoubaoWebActivity` 注册与 `com.larus.nova` 包可见性；`AutoClickService` 的 `TARGET_PACKAGES` 只留 WorkBuddy / Trae Solo。
>    - 保留：数据库里的历史签到记录（统计 / 热力图仍能看到，只是不再新增）。
>
> 恢复方式：若日后豆包重新开放签到写接口，需要按本文件后半部分的实现记录重写 `doubao.py` + provider 注册 + 前端条目 + Android 活动页 —— 不是改个开关就能回来（原代码已不在仓库里）。

---

以下是下线前的实现记录，保留作参考。

参考实现：[tututashu/doubao-auto-checkin](https://github.com/tututashu/doubao-auto-checkin)（MIT）

## 它到底怎么签的（我读完源码后的结论）

**不是调用某个签到 API，而是：读客户端登录态 + Playwright 打开网页点按钮。**

| 环节 | 做法 |
|---|---|
| 凭据 | 读豆包**电脑版客户端**的 Chromium Cookie 库 |
| 主密钥 | `Local State` → `os_crypt.encrypted_key` → 去掉 `DPAPI` 前缀 → `CryptUnprotectData` |
| Cookie 解密 | `v10`/`v11` + 12B nonce + AES-256-GCM（末 16B 是 tag）；新版可能带 32B 域名哈希前缀 |
| 签到页 | `https://www.doubao.com/pc/agent-explore`（首页左下角「每日签到」） |
| 已签判据 | 页面含「每日签到」**但不含**「立即签到」 |
| 点击文案 | 依次尝试：立即签到 / 立即领取 / 签到领额度 / 今日签到 / 签到 |
| 成功文案 | 签到成功 / 已签到 / 领取成功 / 明日再来 |

本程序已按此实现，落在 `backend/signbot/doubao.py`，模式 `mode: "doubao"`。

## 我已实测通过的部分

```
✓ DPAPI 解出 32 字节 AES 主密钥        （Local State → CryptUnprotectData）
✓ AES-GCM 解密 v10 cookie              （含 Chrome 130+ 的 32B 前缀，往返测试 PASS）
✓ 读到 24 个 doubao.com cookie          （客户端数据目录自动定位）
✓ Playwright 启动并打开豆包            （title = "豆包 - 字节跳动旗下 AI 智能助手"）
✓ 通过「仅限电脑版」校验                （UA 需含 SamanthaDoubao/<version>）
✓ 识别已签状态                          （实测返回「今日已签到（页面显示已签）」）
```

真实运行输出：

```json
{"status":"already","message":"今日已签到（页面显示已签）","elapsed":4.05,
 "detail":{"cookies":24,"url":"https://www.doubao.com/pc/agent-explore","before":"already"}}
```

## 关键：两个坑（都已解决）

### 坑 1：客户端锁库

豆包客户端运行时会**独占锁定** `Cookies` 文件（`ERROR_SHARING_VIOLATION = 32`）。
所以读凭据前需要**完全退出豆包电脑版**（读到一次即可，不必每次退出）。

### 坑 2：「该活动仅支持在豆包电脑版参与」

不带客户端 UA 时，活动页直接显示这句话，页面上没有签到按钮。前端用
`/SamanthaDoubao/` 正则校验 `navigator.userAgent`，本程序已固定注入：

```
Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36
(KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36 SamanthaDoubao/2.33.3
```

可用环境变量 `DOUBAO_CLIENT_UA_VERSION` 覆盖版本号（客户端升级后若失效就改这里）。

## 怎么用（推荐：扫码登录）

1. 设置 → 签到配置 → **豆包** → 「豆包直连」→ 点「**扫码登录**」
2. 电脑弹出豆包登录页，右侧就是二维码
3. 用**手机上的豆包 App 或飞书 App** 扫一下
4. 登录成功后窗口自动关闭，凭据存到 `%LOCALAPPDATA%\SigninHub\doubao-state.json`
5. 点「检查凭据」→ 显示「扫码登录态 已就绪」

**为什么推荐这条**：扫码登录后**不需要退出豆包电脑版**。旧方式要读客户端 Cookie 库，
而客户端运行时会独占锁定那个文件，必须先完全退出豆包。

## 凭据来源优先级

```
1. 网页扫码登录态（storage_state）   ← 优先，推荐
2. 豆包电脑版客户端 Cookie 库         ← 兜底，需退出客户端
```

网页登录态失效时（换账号、清登录态）自动回落到客户端方案，功能不会中断。

## 已实测通过

```
✓ 登录页有二维码（豆包/飞书 App 扫码）
✓ 扫码后保存 29 个 cookie（sessionid / sid_tt / uid_tt / sid_guard 齐全）
✓ 网页登录态能打开活动页，服务端不区分来源，无「仅限电脑版」拦截
✓ 已签判据正确：页面含「每日签到」但不含「立即签到」
```

真实运行输出：

```json
{"status":"already","message":"今日已签到（页面显示已签）","elapsed":4.24,
 "detail":{"credential":"web-state","cookies":0,
           "url":"https://www.doubao.com/pc/agent-explore","before":"already"}}
```

## 其他选项

- **无头运行**：默认开，不弹浏览器窗口；关掉能看到实际点击过程，适合排查
- **超时**：默认 45000ms
- **允许提权复制**：仅旧方式（客户端凭据）用，尝试 `esentutl /vss`（会弹 UAC，不一定成功）

## 常见失败

| 现象 | 原因 / 处理 |
|---|---|
| `页面提示「仅支持豆包电脑版参与」` | 登录态或 UA 未被认可 → 重新扫码登录 |
| `没有可用登录态` | 点「扫码登录」扫一次 |
| `Cookie 库被豆包客户端独占锁定` | 旧方式才有：先完全退出豆包电脑版 |
| `未找到豆包电脑版数据目录` | 没装客户端或没登录过（扫码登录可绕过） |
| `Local State 主密钥解密失败` | 不是当前 Windows 用户登录的豆包（扫码登录可绕过） |
| `没找到签到按钮` | 页面改版；把结果里的页面片段发我，我改选择器 |

## 旧方式：客户端凭据（兜底）

不开扫码登录时，程序会：

1. 读 `%LOCALAPPDATA%\Doubao\User Data\...\Network\Cookies`
2. 从 `Local State` 取 DPAPI 主密钥解出 AES key
3. 解密 v10/v11 cookie（AES-256-GCM，兼容 Chrome 130+ 的 32B 前缀）

这条路需要**先完全退出豆包电脑版**。

## 与另外两个服务的对比

| 服务 | 方式 | 需要什么 |
|---|---|---|
| WorkBuddy | 官方 API 直连 | 零配置（DSH vault 有明文 token） |
| Trae Solo | 官方 API 直连 | 登录一次拿 `Cloud-IDE-Token`（8h，可自动续） |
| 豆包 | 扫码登录 + Playwright 点页面 | 扫一次码，之后全自动 |

## 依赖

```bash
python -m pip install pycryptodome playwright
python -m playwright install chromium
```

> 国内网络若卡住，用镜像：
> `set PLAYWRIGHT_DOWNLOAD_HOST=https://cdn.npmmirror.com/binaries/playwright` 再装。
> 我已用这个镜像装好（742MB）。