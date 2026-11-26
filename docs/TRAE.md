# Trae Solo 每日签到（协议来源：star620/TraeTools）

> 📌 **多账号签到报 9074 的完整排查与修复**见 [TRAE-9074-FIX.md](./TRAE-9074-FIX.md)（V2.7 定稿）。
> 本文是协议速查，那份是根因复盘。

参考实现：[star620/TraeTools](https://github.com/star620/TraeTools)（C# Avalonia 桌面工具）

## 之前的实现错在哪

我最初从客户端 `main.js` 里扒到的是 `x-cloudide-token` 头，**那是客户端内部主进程用的**。
实际签到接口认的是另一套头，直接照抄会失败：

| 项 | 错误写法（我的第一版） | 正确写法（TraeTools） |
|---|---|---|
| 认证头 | `x-cloudide-token: <token>` | **`Authorization: Cloud-IDE-JWT <token>`** |
| 设备号 | 无 | **`x-device-id: <16 位数字>`** 必带 |
| 区域 | 无 | **`X-User-Region: cn`** |

缺 `x-device-id` 会被风控拦掉，返回 **9074「参与用户太多」**——这个坑很容易误判成"活动太火爆"。

> ⚠️ **2026-11-21 更正**：9074 的真实成因比这里写的复杂得多——设备号只是四类原因之一，而且**不是最常见的那类**。桌面端 `ob(e)` 会一次注入 **5 个**设备头、请求体是 `{"req_source":1}`、多账号还不能并发 claim。完整结论见 [TRAE-9074-FIX.md](./TRAE-9074-FIX.md)。

## 完整协议

```
POST https://api.trae.cn/trae/api/v2/ug/checkin_credits/status   body {}
POST https://api.trae.cn/trae/api/v2/ug/checkin_credits/claim    body {}
POST https://api.trae.cn/cloudide/api/v3/common/GetUserToken      Cookie: X-Cloudide-Session=<s>
```

请求头（**五条缺一不可**，2026-11-21 更正——原来只写三条，缺后四个设备头会被拒成 9074）：

```
Authorization: Cloud-IDE-JWT <token>
X-User-Region: cn
x-device-id: <16 位数字 Aha 设备号>
x-device-type: Windows
x-os-version: 10.0.19045
x-app-version: 1.107.1
x-device-brand: trae
Content-Type: application/json
User-Agent: Trae/1.107.1          ← 仅签到/领取用；余额查询仍用浏览器 UA
```

请求体不是 `{}`，是 **`{"req_source":1}`**（Solo 版为 `2`）。空 body 也会被拒成 9074。

响应字段：

```json
{ "enable": true, "checked_in": false, "credits": 150, "extra_credits": 50,
  "code": 0, "message": "" }
```

- `code == 0` 为成功；`code == 9074` 是设备号风控
- `credits` 是当前积分，`extra_credits` 是连签/额外奖励（基础 150 + 连签 50 这种）

## token 从哪来

### 1. 登录一次（界面里的「登录 Trae 账号」按钮）

点它会打开 **`https://www.trae.cn/login`**，登录成功后自动从
`localStorage['Cloud-IDE-Token']` 读出 token 存起来。

> ⚠️ **Trae 没有扫码登录。** 我实测了官网首页和登录页：
> - `www.trae.cn/` 首页右上角有个「登录」，但那是进产品控制台，页面本身没有二维码
> - `www.trae.cn/login` 只有三种方式：**手机号+短信验证码 / 抖音登录 / 苹果登录 / 企业账号**
>
> 所以流程是：在弹出来的窗口里用手机号拿验证码登录（最快），登录完回 App 点「检查凭据」。

登录态存在独立 profile：`%LOCALAPPDATA%\SigninHub\trae-profile`，不碰你日常用的浏览器。

### 2. 手动粘贴

F12 打开 `www.trae.cn` 并登录，Console 执行：

```js
localStorage.getItem('Cloud-IDE-Token')
```

把结果粘进「Cloud-IDE-Token」框。

### 3. 会话换 token

如果有 `X-Cloudide-Session` cookie（约 14 天有效），程序会调
`/cloudide/api/v3/common/GetUserToken` 自动换一个 8 小时有效的新 token。

## x-device-id 从哪来

**自动读取**，不用手填。从 Trae 客户端的：

```
%APPDATA%\TRAE SOLO CN\User\globalStorage\storage.json
```

用正则 `iCubeAuthInfo://icube-dc:(\d{8,})` 抓 16 位数字。本项目实测值：`3893788684059587`
（和客户端日志里的 `[ICDRS] did:` 一致）。

也可以手动填，格式是 **16 位纯数字**——用 GUID/UUID 会被风控拒。

> ⚠️ **一个真实设备号一天只能签一个账号。** 多账号场景下每个账号要各自的机器号（在各自客户端登录一次生成），不能共用。
> 另外：`x-device-id` 是**机器指纹**不是账号凭据，桌面客户端切换账号时并不换它——`guaranteedDeviceId` 来自 Electron 原生 `ahaDeviceService`，本机维度。

## 为什么读不到桌面客户端的 token

我试过所有本地位置，全部失败（TraeTools 也是同样结论，所以它才做了独立登录窗口）：

| 位置 | 结果 |
|---|---|
| `Local Storage\leveldb\*` | 无 JWT、无 session |
| `User\globalStorage\state.vscdb` | 70 个 key，只有 UI 状态 |
| `storage.json` | 只有设备号，无 token |
| Windows 凭据管理器 | 无 Trae 条目 |
| 客户端日志 | `x-cloudide-token: '******'` 脱敏 |

客户端 token 用 Electron `safeStorage`（DPAPI）加密，且只在主进程内存里用。
**唯一可靠的路径就是走网页端 localStorage**——这也是 TraeTools 的做法。

## 界面上的两个按钮

- **登录 Trae 账号**：打开 `www.trae.cn/login`，登录后自动存 token
- **检查凭据**：显示 `token 已就绪（xxx 字符）· 设备号 3893788684059587（来自 TRAE SOLO CN）`

## 常见失败

| 现象 | 原因 |
|---|---|
| `code=9074` | **按此顺序排查**：① 缺 `x-device-type`/`x-os-version`/`x-app-version`/`x-device-brand` 四个设备头；② 请求体不是 `{"req_source":1}`；③ 多账号并发 claim；④ 设备号确实没在该账号下注册过（最后才考虑）。详见 [TRAE-9074-FIX.md](./TRAE-9074-FIX.md) |
| `code!=0` 其他 | 看 message；多半是账号没资格或活动结束 |
| `token 缺失` | 还没扫码登录 |
| `凭据被拒绝 401/403` | token 过期（8 小时），重新扫码 |

## 与其他服务对比

| 服务 | 方式 | 需要什么 |
|---|---|---|
| WorkBuddy | API 直连 | 零配置（vault 有明文 token） |
| Trae Solo | API 直连 | 扫码登录一次（token 8h，可自动续） |
| 豆包 | ~~客户端凭据 + Playwright~~ | **已下线（2026-11-20）**：服务端 `landing/checkin` 固定返回 `710010702`，官方客户端手动点也失败 |