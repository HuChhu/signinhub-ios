#  成品下载（release）

这里放的是 SigninHub 的**编译成品**，不想自己编译源码的直接下载使用。

| 文件 | 说明 |
|---|---|
| `SigninHub-mobile.apk` | 安卓端 App（控制台 + 提醒 + 无障碍自动签到兜底） |
| `SHA256SUMS.txt` | 文件校验值，下载后核对完整性 |

## 校验方法

下载后校验 SHA256 与 `SHA256SUMS.txt` 中记录一致：

```powershell
Get-FileHash .\SigninHub-mobile.apk -Algorithm SHA256
```

## 安装

安卓手机直接安装 apk（需允许"安装未知来源应用"）。
电脑/服务器端后端请用仓库根目录的 `backend/` 源码运行，见 [README](../README.md)。

## 声明

成品与源码均按 **CC BY-NC-SA 4.0** 授权：禁止商用，二创需署名并以相同许可证开源。完整条款见仓库根目录 [LICENSE](../LICENSE)。