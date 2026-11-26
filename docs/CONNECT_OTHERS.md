# 怎么把新服务接上（Trae Solo 等）

> WorkBuddy 已经通了（官方协议直连，见 [WORKBUDDY.md](WORKBUDDY.md)）。
> 新服务**没有公开协议**，所以要用下面两种方式之一。两者都不需要你写代码。
>
> **豆包已下线（2026-11-20）**：服务端对签到写接口固定返回 `710010702`，官方客户端手动点签到也失败，
> 属平台侧风控封禁，客户端无修复空间。本文里的豆包示例仅作占位参考，别再照着配了，详见 [DOUBAO.md](DOUBAO.md)。

---

## 方式 A：粘贴 cURL 一键填充（最快，约 30 秒）

这是给没有公开协议的服务准备的主力方式。原理：你在浏览器里点一次签到，把那次请求原样复制给我，
界面自动拆出 URL / 方法 / 请求头 / 请求体。

### 步骤

1. **在电脑上**打开对应服务的签到页面并登录：
   - Trae Solo：`https://www.trae.cn/`（或国际版 `trae.ai`）登录后进入积分 / 签到入口
2. 按 `F12` 打开 DevTools → **Network（网络）** 面板，勾上 `Preserve log`
3. 点页面上的**「签到 / 领取」按钮**（就点一次）
4. 在 Network 列表里找到刚发出去的那条请求（通常叫 `checkin`、`sign`、`daily`、`claim`、`task` 之类，方法是 `POST`）
5. **右键该请求 → Copy → Copy as cURL (bash)**
6. 回到手机 App：**设置 → 签到配置 → 展开对应服务（如 Trae Solo）→ 接口模式**
7. 把复制的内容**粘贴进「一键粘贴 cURL」框** → 点**「解析并填入下面的字段」**
8. 检查「成功判定字段 / 成功判定值」：
   - 返回体如果长这样 `{"code":0,"msg":"ok"}` → 填 `code` / `0`
   - 如果是 `{"success":true}` → 填 `success` / `true`
   - 如果是 `{"data":{"status":1}}` → 填 `data.status` / `1`
9. 点 **保存** → 点 **测试签到** → 看到「成功 / already」就成了

### 小技巧

- 如果抓到的请求带 `Content-Type: application/json`，请求体会自动格式化成 JSON，方便你核对。
- 抓包时**不要复制带 `--compressed` 以外乱七八糟的**，解析器会忽略无关 flag。
- Cookie 过期后重新走一遍第 2–7 步覆盖即可（通常几周甚至几个月）。
- 判定不准时，把「测试签到」失败结果里的 `detail`（原始返回体）发我，我帮你定 `success_expr` / `success_value`。

---

## 方式 B：浏览器模式（不用抓包，但要在电脑装 Playwright）

程序会自己开一个浏览器、用你保存的登录态、去点「签到」按钮。适合界面稳定、但接口加签/加密的服务。

### 一次性安装

```bash
cd signin-hub\backend
pip install playwright
playwright install chromium
```

### 捕获登录态（每个服务一次）

1. 在 `backend/data/config.json` 里把该服务的 `browser.login_url` / `browser.sign_url` 改成真实地址，例如：

```json
"traesolo": {
  "mode": "browser",
  "browser": {
    "login_url": "https://www.trae.cn/",
    "sign_url": "https://www.trae.cn/",
    "click_selectors": ["text=签到", "text=每日签到", "text=领取"],
    "headless": true,
    "storage_state": "trae_state.json"
  }
}
```

2. 运行（会弹窗，你手动登录一次）：

```bash
python tools\login.py traesolo
```

3. 回到 App 设置页该服务选「浏览器模式」，点「测试签到」。

### 选择器怎么改

`click_selectors` 支持 Playwright 语法，按命中概率从高到低排：

```
text=签到
text=立即签到
text=领取
button:has-text('签到')
[aria-label="签到"]
```

「未找到签到按钮」时：先确认今天是不是已经签过了，再换选择器。

---

## 我探测过但走不通的路（说明一下，免得你白折腾）

| 尝试 | 结果 |
|---|---|
| 找 Trae Solo 的本地登录态（`%APPDATA%\TRAE SOLO CN\Network\Cookies`） | 文件存在，但 Trae 运行时会**独占锁定**，且 cookie 经 DPAPI 加密；读不到可用凭据 |
| 找豆包客户端 cookie（`%APPDATA%\Doubao` / `%LOCALAPPDATA%\Doubao`） | 能解出来（DPAPI + AES-GCM，28 个 cookie 齐全），但**签到写接口已被服务端风控**：`landing/checkin` 固定返回 `710010702`，官方客户端手动点也同样失败 → 豆包签到已于 2026-11-20 下线 |
| 找公开协议实现 | WorkBuddy 有现成参考（[dsh-connect-workbuddy-models](https://github.com/DragonHorizon-xtihc/dsh-connect-workbuddy-models)）；Trae Solo 我没找到对应仓库，网络搜索接口当前也不可用 |

**所以结论是**：Trae Solo 走 A（cURL 一次）或 B（Playwright）最实际。等你抓一次，
把返回体贴给我，我可以直接把 `config.json` 给你配好，甚至把它做成和 WorkBuddy 一样的「直连」模式。

---

## 三者对照

| 服务 | 当前状态 | 推荐方式 |
|---|---|---|
| WorkBuddy | ✅ 已直连（实测「连续 20 天 · 今日 100 积分」） | 什么都不用做 |
| Trae Solo | ⬜ 待配置 | A：粘贴 cURL（30 秒） |
| 豆包 | ❌ 已下线（2026-11-20 平台风控 710010702） | 无，等官方恢复 |