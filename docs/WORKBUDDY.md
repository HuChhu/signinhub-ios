# WorkBuddy 真签到（官方协议）

参考实现：[DragonHorizon-xtihc/dsh-connect-workbuddy-models](https://github.com/DragonHorizon-xtihc/dsh-connect-workbuddy-models)
（协议来自其 `src/upstream.ts`，本机实测通过）。

## 结论：已能真签到

本程序内置 `workbuddy` 直连模式（`mode: "wb"`），实测返回真实数据：

```
status : already
points : +100
message: 今日已签到 · 连续 20 天 · 今日 100 积分
```

## 协议要点

| 项 | 值 |
|---|---|
| 签到状态 | `POST {billing}/v2/billing/meter/checkin-activity-status`，body `{}` |
| 领取签到 | `POST {billing}/v2/billing/meter/daily-checkin`，body `{}` |
| 刷新令牌 | `POST {chat}/v2/plugin/auth/token/refresh`，头 `X-Refresh-Token` + `X-Auth-Refresh-Source: workbuddy` |
| 国内基址 | chat `https://copilot.tencent.com` / billing `https://www.codebuddy.cn` |
| 国际基址 | 两者都是 `https://www.workbuddy.ai` |
| 信封 | `{"code":0,"msg":...,"data":{...}}`，`code == 0` 为成功 |

请求头（billing）：

```
Authorization: Bearer <accessToken>
Accept: application/json
Content-Type: application/json
X-User-Id: <uid>
X-Enterprise-Id / X-Tenant-Id: <enterpriseId>
X-Domain: <domain>
X-Requested-With: XMLHttpRequest
Origin / Referer: <billing 基址>
User-Agent: CLI/2.63.2 CodeBuddy/2.63.2
```

状态字段：`today_checked_in`、`streak_days`、`daily_credit`、`today_credit`、`active`、`claim_button_text`。
领取返回：`credit`、`streak_days`。

> 幂等性：先查状态，`today_checked_in == true` 就返回「已签到」，不会重复领取。

## 凭据从哪来

按优先级自动发现（只读，绝不回写）：

1. **DSH vault**：`~/.dsh/workbuddy-vault/{cn,global}/*.json` —— **明文** `auth.accessToken`，最省事，实测可用。
2. **WorkBuddy 桌面端**：`%LOCALAPPDATA%` / `%APPDATA%` 下
   `CodeBuddyExtension\Data\Public\auth\*.info`。
   ⚠️ Windows 新版把 `accessToken` 存成 `$wbEncrypted` 信封（AES-256-GCM，密钥要从桌面端可执行文件取），
   本程序不持有该 at-rest 密钥，**会明确报告「加密信封」而不是假装没有账号**。
3. **手动**：设置页填 `accessToken`。

## 怎么用

**A. 零配置（推荐）**：本机已装过 DSH WorkBuddy 插件 / 登录过桌面端 → 设置页 WorkBuddy 选「WB 直连」，
`accessToken` 留空，点「测试签到」。

**B. 手动填 token**：把桌面端 `.info` 里的 token（或浏览器 DevTools 里 `Authorization: Bearer ...` 的值）
贴进设置页 `accessToken`。

**C. 命令行自检**（只读，不签到）：

```bash
cd signin-hub/backend
python tools/wb_status.py     # 列出发现到的账号、今日是否已签、连续天数
python tools/tryone.py workbuddy   # 真跑一次（已签则显示 already）
```

## 区域

`wb.region` 可选 `cn` / `global` / 留空自动。同一台机器可能同时有国内版和国际版账号，
自动模式下会按凭据的 `domain` 判定，取最新签发的那一份。

## 常见失败

| 现象 | 原因 / 处理 |
|---|---|
| `凭据被拒绝 (401/403)` | token 过期；若有 refreshToken 会自动续期一次，仍失败就重新登录桌面端 |
| `no usable credential` | 三种来源都没有 → 手动填 accessToken |
| 提示「加密信封」 | 桌面端 .info 被加密，属正常；改用 vault 或手动填 |
| `active: false` | 该账号签到活动未开启（国际版常见），不是程序问题 |