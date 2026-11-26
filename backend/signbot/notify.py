"""通知推送：钉钉 / 企业微信 / 飞书 / PushPlus / Server酱 / Bark / WxPusher / 通用 webhook。

要点：
1. 这几家的 webhook 出错时**仍然返回 HTTP 200**，错误码在响应体里
   （企业微信 errcode / 钉钉 errcode / 飞书 code / PushPlus code / Server酱 code），
   所以必须校验 body，否则「key 写错」也会显示推送成功。
2. 渠道既可以显式选（kind），也可以由 webhook 的 host 自动识别 ——
   用户把 PushPlus 的 url 粘进「通用」也能正常推。
3. PushPlus 只需要 token：允许只填 token，或填 `/send/<token>` 的完整地址，
   统一归一化成 https://www.pushplus.plus/send。
4. Bark（iOS）/ WxPusher（微信服务号）同样只需一串 key：
   Bark 填 `https://api.day.app/<key>` 或直接填 key；
   WxPusher 填 `https://wxpusher.zjiecode.com/api/send/message/<appToken>` 或直接填 appToken，
   UID 放在「密钥」栏（多个用逗号分隔，留空则发给该应用全部订阅者）。
"""
import base64
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.parse
import urllib.request

from . import db, netguard

KINDS = ("wecom", "dingtalk", "feishu", "pushplus", "serverchan", "bark", "wxpusher", "generic")

PUSHPLUS_ENDPOINT = "https://www.pushplus.plus/send"
BARK_ENDPOINT = "https://api.day.app/push"
WXPUSHER_ENDPOINT = "https://wxpusher.zjiecode.com/api/send/message"


class PushError(Exception):
    pass


def _post_json(url: str, payload: dict, timeout: int = 10, strict: bool = False):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    # 出站目标校验：url 来自用户填的 webhook，必须过 netguard 的地址/协议/跳转检查
    #
    # H10：strict=True 时连 RFC1918 私网也拒绝。默认 False —— 用户自建的内网
    # webhook（NAS 通知、自建 Bot）本来就是裸 HTTP 私网地址，一刀切会打死正常用法。
    # 但只要这台机器把后端暴露到公网 / 给同网段的人发了口令，私网可达就成了
    # 「借你的后端打你内网」的跳板 —— 那种部署下应打开 notify.strict_target。
    try:
        return netguard.open_checked(req, timeout=timeout,
                                     allow_private_http=not strict)
    except netguard.BlockedTarget as e:
        raise PushError(str(e))


def _post_form(url: str, fields: dict, timeout: int = 10, strict: bool = False):
    data = urllib.parse.urlencode(fields).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    # H10：同 _post_json，strict 打开后拒绝一切私网目标
    try:
        return netguard.open_checked(req, timeout=timeout,
                                     allow_private_http=not strict)
    except netguard.BlockedTarget as e:
        raise PushError(str(e))


def _normalize_kind(kind: str) -> str:
    k = (kind or "generic").strip().lower()
    alias = {
        "wechat": "wecom", "wxwork": "wecom", "qywx": "wecom", "work_weixin": "wecom",
        "wecom": "wecom",
        "ding": "dingtalk", "dingding": "dingtalk", "dingtalk": "dingtalk",
        "lark": "feishu", "feishu": "feishu",
        "pushplus": "pushplus", "push_plus": "pushplus", "pp": "pushplus",
        "serverchan": "serverchan", "sct": "serverchan", "server_chan": "serverchan",
        "bark": "bark", "ios": "bark",
        "wxpusher": "wxpusher", "wx_pusher": "wxpusher", "wxp": "wxpusher",
        "generic": "generic", "custom": "generic", "other": "generic",
    }
    return alias.get(k, "generic")


def detect_kind(url: str, fallback: str = "generic") -> str:
    """按 host 自动识别渠道；识别不出就用 fallback。"""
    try:
        host = (urllib.parse.urlparse(url or "").hostname or "").lower()
    except Exception:
        return _normalize_kind(fallback)
    if not host:
        return _normalize_kind(fallback)
    if "qyapi.weixin.qq.com" in host:
        return "wecom"
    if "dingtalk.com" in host:
        return "dingtalk"
    if "feishu.cn" in host or "larksuite.com" in host:
        return "feishu"
    if "pushplus" in host:
        return "pushplus"
    if "ftqq.com" in host or "sctapi" in host or "serverchan" in host:
        return "serverchan"
    if "day.app" in host or "bark" in host:
        return "bark"
    if "wxpusher" in host:
        return "wxpusher"
    return _normalize_kind(fallback)


def pushplus_token(url: str) -> str:
    """从 https://www.pushplus.plus/send/<token> 或 ?token=<token> 里取 token。"""
    if not url:
        return ""
    if "://" not in url:
        # 用户只填了 token
        return url.strip()
    try:
        p = urllib.parse.urlparse(url)
        qs = urllib.parse.parse_qs(p.query)
        if qs.get("token"):
            return qs["token"][0].strip()
        segs = [s for s in p.path.split("/") if s]
        # /send/<token> 或 /batchSend/<token>
        if len(segs) >= 2 and segs[0] in ("send", "batchSend"):
            return segs[1].strip()
        if len(segs) == 1 and segs[0] not in ("send", "batchSend"):
            return segs[0].strip()
    except Exception:
        pass
    return url.strip()


def last_path_token(url: str) -> str:
    """取地址路径最后一段当 key。

    给 Bark（https://api.day.app/<key>）和 WxPusher
    （https://wxpusher.zjiecode.com/api/send/message/<appToken>）用 —— 它们的 key 在
    路径末段，段数不固定，不能用 pushplus 那套 segs[0]/segs[1] 规则。
    """
    if not url:
        return ""
    s = url.strip()
    if "://" not in s:
        return s
    try:
        p = urllib.parse.urlparse(s)
        segs = [x for x in p.path.split("/") if x]
        if segs:
            return urllib.parse.unquote(segs[-1]).strip()
    except Exception:
        pass
    return s


def server_chan_key(raw: str) -> str:
    """从 Server酱 的各种填法里抠出 SendKey。

    历史 bug：原来直接把整串地址 POST 过去，于是
      · 填了 ``https://sctapi.ftqq.com/<SCTxxx>.send``（placeholder 带尖括号）
        → 发出去的 key 成了 ``<SCTxxx>.send``
      · 服务端回 ``HTTP 400 {"message":"[AUTH]错误的Key","code":40001}``
    现在统一清洗：去尖括号/引号/空白、去掉 ``.send`` 后缀，只留 key 本身。
    """
    if not raw:
        return ""
    s = raw.strip()
    for ch in ("<", ">", '"', "'", " ", "\t", "\n"):
        s = s.replace(ch, "")
    if "://" in s or "/" in s:
        s = last_path_token(s)
    if s.endswith(".send"):
        s = s[:-5]
    return s.strip().strip("<>").strip()


def _build_payload(kind: str, title: str, body: str) -> dict:
    text = f"{title}\n{body}"
    if kind == "wecom":
        return {"msgtype": "text", "text": {"content": text}}
    if kind == "dingtalk":
        return {"msgtype": "text", "text": {"content": text}}
    if kind == "feishu":
        return {"msg_type": "text", "content": {"text": text}}
    # generic
    return {"title": title, "text": body, "content": text}


def _build_pushplus(title: str, body: str) -> dict:
    """PushPlus 用 html 模板发到个人微信，换行和加粗都能保留。"""
    now = time.strftime("%Y-%m-%d %H:%M:%S")
    lines = [ln for ln in (body or "").splitlines() if ln.strip()]
    html = "<b>" + _esc(title) + "</b><br>" + "<br>".join(_esc(ln) for ln in lines)
    html += f"<br><br><span style=\"color:#999\">{now}</span>"
    return {
        "title": title or "签到中心",
        "content": html,
        "template": "html",
    }


def _esc(s: str) -> str:
    """HTML 转义。

    以前只转 & < >，不转引号 —— PushPlus / 钉钉 markdown 里一旦出现 `"` 或 `'`
    （签到失败消息里带 JSON 片段很常见），属性值会被截断，甚至把后半段当标签解析。
    现在五个字符全转，引号用实体，反引号也一并处理（markdown 代码块同样会破坏排版）。
    """
    return (
        (s or "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
        .replace("`", "&#96;")
    )


def _dingtalk_signed_url(url: str, secret: str) -> str:
    """钉钉「加签」：timestamp + HMAC-SHA256(secret) -> sign 参数。"""
    if not secret:
        return url
    ts = str(round(time.time() * 1000))
    string_to_sign = f"{ts}\n{secret}".encode("utf-8")
    digest = hmac.new(secret.encode("utf-8"), string_to_sign, hashlib.sha256).digest()
    sign = urllib.parse.quote_plus(base64.b64encode(digest).decode())
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}timestamp={ts}&sign={sign}"


def _check_body(kind: str, text: str):
    """校验各家的业务错误码。失败抛 PushError。"""
    if not text.strip():
        return
    try:
        doc = json.loads(text)
    except Exception:
        return  # 非 JSON，交给 HTTP 状态码判断
    if not isinstance(doc, dict):
        return

    def first_int(*names):
        for n in names:
            v = doc.get(n)
            if isinstance(v, int):
                return v
        return None

    if kind in ("wecom", "dingtalk"):
        code = first_int("errcode", "error_code")
        if code is not None and code != 0:
            raise PushError(f"{doc.get('errmsg') or doc.get('message') or '未知错误'} (errcode={code})")
        return
    if kind == "feishu":
        code = first_int("code", "StatusCode")
        if code is not None and code != 0:
            raise PushError(f"{doc.get('msg') or doc.get('StatusMessage') or '未知错误'} (code={code})")
        data = doc.get("data")
        if isinstance(data, dict):
            inner = first_int("code")
            if inner is None:
                v = data.get("code")
                if isinstance(v, int):
                    inner = v
            if inner is not None and inner not in (0,):
                raise PushError(f"{data.get('msg') or '未知错误'} (data.code={inner})")
        return
    if kind == "pushplus":
        # PushPlus 成功返回 {"code":200,"msg":"请求成功"}
        v = doc.get("code")
        if isinstance(v, int) and v != 200:
            raise PushError(f"{doc.get('msg') or '未知错误'} (code={v})")
        return
    if kind == "serverchan":
        v = doc.get("code")
        if isinstance(v, int) and v != 0:
            raise PushError(f"{doc.get('message') or '未知错误'} (code={v})")
        return
    if kind == "bark":
        # 成功 {"code":200,"message":"success"}；失败 code 非 200
        v = doc.get("code")
        if isinstance(v, int) and v != 200:
            raise PushError(f"{doc.get('message') or '未知错误'} (code={v})")
        return
    if kind == "wxpusher":
        # 成功 {"code":1000,"msg":"处理成功","data":[{...,"code":1000}]}
        v = doc.get("code")
        if isinstance(v, int) and v != 1000:
            raise PushError(f"{doc.get('msg') or '未知错误'} (code={v})")
        data = doc.get("data")
        if isinstance(data, list):
            bad = [d for d in data if isinstance(d, dict) and isinstance(d.get("code"), int) and d.get("code") != 1000]
            if bad:
                raise PushError(f"部分 UID 失败：{bad[0].get('status') or bad[0].get('code')}")


def _format_serverchan(title: str, body: str) -> dict:
    return {"title": title, "desp": body.replace("\n", "\n\n")}


def _send(kind: str, url: str, title: str, body: str, secret: str,
          strict: bool = False):
    """真正发出请求。返回 (kind, text)。失败抛异常。

    strict=True（对应 notify.strict_target）时，连 RFC1918 私网目标也拒绝：
    后端一旦被放到公网 / 口令发给了同网段的人，「能打私网」就等于「借你的
    后端打你内网」。只有走用户自填 url 的 generic / dingtalk 分支才受它约束 ——
    官方渠道（PushPlus / Bark / Server酱 / WxPusher）的 endpoint 是常量，
    不受用户地址影响。
    """
    if kind == "pushplus":
        # 统一发到官方 endpoint，token 从用户给的地址里抽
        token = pushplus_token(url)
        if not token:
            raise PushError("PushPlus 需要 token（地址填 /send/<token> 或直接填 token）")
        payload = _build_pushplus(title, body)
        payload["token"] = token
        status, text = _post_json(PUSHPLUS_ENDPOINT, payload)
    elif kind == "serverchan":
        key = server_chan_key(url)
        if not key:
            raise PushError("Server酱 需要 SendKey（地址填 https://sctapi.ftqq.com/<SendKey>.send 或直接填 SendKey）")
        payload = _format_serverchan(title, body)
        status, text = _post_form(f"https://sctapi.ftqq.com/{key}.send", payload)
    elif kind == "bark":
        key = last_path_token(url)
        if not key:
            raise PushError("Bark 需要 device key（地址填 https://api.day.app/<key> 或直接填 key）")
        payload = {
            "device_key": key,
            "title": title or "签到中心",
            "body": body or "",
            "group": "SigninHub",
            "icon": "https://www.codebuddy.cn/favicon.ico",
        }
        status, text = _post_json(BARK_ENDPOINT, payload)
    elif kind == "wxpusher":
        app_token = last_path_token(url)
        if not app_token:
            raise PushError("WxPusher 需要 appToken（地址填 .../api/send/message/<appToken> 或直接填 appToken）")
        uids = [x.strip() for x in (secret or "").split(",") if x.strip()]
        if not uids:
            raise PushError("WxPusher 需要在「密钥」栏填 UID（多个用逗号分隔）")
        payload = {
            "appToken": app_token,
            "content": "<b>" + _esc(title) + "</b><br>" + "<br>".join(
                _esc(ln) for ln in (body or "").splitlines() if ln.strip()),
            "summary": (title or "")[:20],
            "contentType": 2,
            "uids": uids,
        }
        status, text = _post_json(WXPUSHER_ENDPOINT, payload)
    else:
        payload = _build_payload(kind, title, body)
        if kind == "dingtalk":
            url = _dingtalk_signed_url(url, (secret or "").strip())
        # H10：只有这条分支的 url 完全来自用户配置，strict 才有意义
        status, text = _post_json(url, payload, strict=strict)

    if not (200 <= status < 300):
        raise PushError(f"HTTP {status}: {text[:200]}")
    _check_body(kind, text)
    return text


def push(cfg: dict, title: str, body: str) -> bool:
    """按配置推送通知。成功返回 True；失败写 notify_log 并返回 False。不抛异常。"""
    ncfg = (cfg or {}).get("notify", {})
    if not ncfg.get("enabled"):
        db.add_notify_log("info", title, body, True)
        return True
    url = (ncfg.get("webhook_url") or "").strip()
    if not url:
        db.add_notify_log("info", title, body, True)
        return True

    kind = detect_kind(url, ncfg.get("kind"))
    strict = bool(ncfg.get("strict_target"))
    try:
        _send(kind, url, title, body, (ncfg.get("secret") or "").strip(), strict=strict)
    except PushError as e:
        db.add_notify_log("error", title, f"{body}\n[push error] {e}", False)
        return False
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as e:
        db.add_notify_log("error", title, f"{body}\n[push error] {e}", False)
        return False
    except Exception as e:  # 兜底
        db.add_notify_log("error", title, f"{body}\n[push error] {e}", False)
        return False

    db.add_notify_log("info", title, body, True)
    return True


def push_detail(cfg: dict, title: str, body: str) -> dict:
    """给界面用：返回 {ok, error}，便于把失败原因显示出来。"""
    ncfg = (cfg or {}).get("notify", {})
    url = (ncfg.get("webhook_url") or "").strip()
    if not ncfg.get("enabled"):
        return {"ok": False, "error": "通知未开启（请勾选开关）"}
    if not url:
        return {"ok": False, "error": "还没填 Webhook 地址"}

    kind = detect_kind(url, ncfg.get("kind"))
    strict = bool(ncfg.get("strict_target"))
    try:
        _send(kind, url, title, body, (ncfg.get("secret") or "").strip(), strict=strict)
    except PushError as e:
        db.add_notify_log("error", title, f"{body}\n[push error] {e}", False)
        return {"ok": False, "error": str(e), "kind": kind}
    except Exception as e:
        db.add_notify_log("error", title, f"{body}\n[push error] {e}", False)
        return {"ok": False, "error": str(e), "kind": kind}

    db.add_notify_log("info", title, body, True)
    return {"ok": True, "error": "", "kind": kind}
