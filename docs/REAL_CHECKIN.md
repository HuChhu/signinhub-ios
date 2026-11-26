# 从「演示模式」切换到「真正签到」

演示模式不是开关，而是保险：只要 `config.json` 里还是 `WORKBUDDY_TOKEN` / `TRAESOLO_COOKIE` 这类**占位符**，
程序就绝不发真实请求，只在本地模拟成功。要真签到 = 把占位符换成**你账号的真实数据**。

先跑体检，看还差什么：

```bash
cd signin-hub/backend
python tools/check.py
```

WorkBuddy / Trae Solo 都没有公开签到 API，所以只有两条路：

- **路径 A：浏览器模式**（强烈推荐，不用抓包）——程序打开网页、用你保存的登录态、点「签到」。
- **路径 B：接口模式**（最快最稳，但要抓包）——直接用你抓到的签到接口 + Cookie。

> **豆包已下线（2026-11-20）**：服务端对 `landing/checkin` 固定返回 `710010702`，官方客户端手动点也失败，
> 属平台侧风控封禁。配置里已置 `providers.doubao.enabled = false`，本文不再以豆包举例，详见 [DOUBAO.md](DOUBAO.md)。

---

## 路径 A：浏览器模式（推荐先走这个）

### 1. 安装 Playwright（只需一次，约 1 分钟）

```bash
cd signin-hub/backend
pip install playwright
playwright install chromium
```

### 2. 改配置：把该服务的 `mode` 设为 `browser`，并填真实页面地址

打开 `backend/data/config.json`，以 Trae Solo 为例：

```json
"traesolo": {
  "enabled": true,
  "mode": "browser",
  "http": { "...": "保持不动，浏览器模式用不到它" },
  "browser": {
    "login_url": "https://www.trae.cn/",
    "sign_url": "https://www.trae.cn/",
    "click_selectors": ["text=签到", "text=每日签到", "text=领取", "button:has-text('签到')"],
    "headless": true,
    "storage_state": "trae_state.json"
  }
}
```

> `sign_url` 要填**签到入口所在页面**的真实地址；`click_selectors` 是「签到」按钮的匹配规则，
> 支持 `text=签到`（按文字找）和 CSS 选择器等。找不到按钮时看日志提示再微调。

### 3. 捕获登录态（关键一步）

```bash
python tools/login.py traesolo
```

会弹出一个 Chromium 窗口 → 你在里面正常登录该服务（扫码/账密都行）→ 回到命令行**按回车**。
登录态保存到 `backend/data/trae_state.json`，之后签到自动复用，不用每天登录。

WorkBuddy 同理：`python tools/login.py workbuddy`（记得先在该服务的 `browser` 段填好 `login_url` / `sign_url`）。

### 4. 验证

```bash
python tools/check.py      # 应显示 [v] browser mode ready
python run.py              # 启动后，网页里点该服务的「签到」
```

日志里出现「浏览器模式：已点击签到按钮」即为真签到成功；返回「未找到签到按钮」多半是已签过或选择器要改。

---

## 路径 B：接口模式（想更快更稳就抓包）

### 1. 抓到真实签到请求

用 **Chrome DevTools → Network**、或 Charles / Fiddler / mitmproxy：
1. 正常登录后，在官方 App/网页里**手动点一次「签到」**；
2. 找到那一条请求，记下：**URL、Method、请求头（尤其 Cookie / Authorization）、请求体、成功时的返回 JSON**；
3. 从返回 JSON 里确认哪个字段表示成功（如 `{"code":0}` 就是 `success_expr=code`, `success_value=0`）。

### 2. 填进 config.json

```json
"traesolo": {
  "enabled": true,
  "mode": "http",
  "http": {
    "method": "POST",
    "url": "https://实际抓到的签到接口",
    "headers": {
      "Content-Type": "application/json",
      "Cookie": "你抓到的完整 Cookie",
      "User-Agent": "你抓到的 UA"
    },
    "body": { "按抓包原样填": "字段值" },
    "body_type": "json",
    "success_expr": "code",
    "success_value": 0
  }
}
```

### 3. 验证

```bash
python tools/check.py
```

显示 `[v] http config complete -> will send a REAL request` 即配置完成。

---

## 常见问题
- **点了还显示「演示」**：`check.py` 会指出是哪个字段还是占位符，改掉即可（保存后无需重启，下次签到即生效）。
- **Cookie 多久过期**：各服务不同。接口模式重新抓一次覆盖；浏览器模式重跑 `tools/login.py` 覆盖登录态。
- **想先安全试跑**：把该服务 `enabled` 设为 `false`，或 `mode` 暂时留 `http`（占位即演示）。
- **网页按钮只对「已启用」的服务生效**：`enabled:false` 的服务不会出现在一键签到里（豆包即如此）。

---

## 安全提醒
`config.json` 里有你的 Cookie / 登录态，等同于账号凭证：
- 只放在自己电脑上，别提交到 Git（仓库 `.gitignore` 已排除 `backend/data/`）；
- 别把 `data/` 目录发给别人；
- 手机网页端只通过局域网访问，且可设 `server.api_token` 防止被同一网络的人乱点。