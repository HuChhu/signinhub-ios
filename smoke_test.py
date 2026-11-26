# -*- coding: utf-8 -*-
"""冒烟测试：stub /api，加载真实页面，切四个视图，验证渲染无异常。

为什么不再用 smoke.html：那份 DOM 是早期手抄的快照，早就跟 index.html 脱节了
（缺 29 个 id，连 #schedJitter 都没有），于是 app.js 初始化时抛 TypeError、
window.__signhub 根本没导出，测试变成「测一个不存在的页面」。
现在直接服务 backend/web 目录、打开真实的 index.html，测的就是线上那份。
"""
import json, os, sys, threading, functools
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from playwright.sync_api import sync_playwright

# 不再写死 D:\RUANJIAN\signin-hub（那台机器的路径早就不存在了）：
# 直接用本文件所在目录，仓库搬到哪都能跑。
ROOT = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(ROOT, "backend", "web")

def serve():
    handler = functools.partial(SimpleHTTPRequestHandler, directory=WEB)
    srv = ThreadingHTTPServer(("127.0.0.1", 18899), handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv

STATUS = {
    "day": "2026-02-14", "streak": 23, "local_mode": True,
    # 与线上一致：现行五家是 WorkBuddy / Trae Solo / Qoder CN / MiniMax Code / 华为码道。
    # 豆包已于 2026-11-20 下线并在 V2.25 清理里彻底移除（provider / 路由 / 前端条目），
    # 所以这里也不再塞它的桩数据 —— 留着会让 tokenRows 断言永远多一行。
    "providers": [
        {"key": "workbuddy", "label": "WorkBuddy", "enabled": True, "mode": "wb", "ready": True, "issues": [], "status": "already", "message": "今天已签", "points": "+100", "time": "2026-02-14 08:00:11"},
        {"key": "traesolo", "label": "Trae Solo", "enabled": True, "mode": "trae", "ready": True, "issues": [], "status": "idle", "message": "今日尚未执行", "points": "", "time": ""},
        {"key": "qoder", "label": "Qoder CN", "enabled": True, "mode": "qoder", "ready": True, "issues": [], "status": "success", "message": "领取成功", "points": "+10", "time": "2026-02-14 08:02:33"},
        {"key": "minimax", "label": "MiniMax Code", "enabled": True, "mode": "minimax", "ready": True, "issues": [], "status": "success", "message": "签到成功", "points": "", "time": "2026-02-14 08:03:10"},
        {"key": "huawei", "label": "华为码道", "enabled": True, "mode": "huawei", "ready": True, "issues": [], "status": "already", "message": "今日已签到（100 积分）", "points": "", "total_points": 1200, "time": "2026-02-14 08:04:00"},
    ],
    "scheduler": {"enabled": True, "times": ["08:30"]},
    "week": [
        {"day": f"2026-02-{8+i:02d}", "weekday": "日一二三四五六"[(8+i) % 7], "done": 2 if i < 6 else 1, "total": 2, "ok": False,
         "statuses": {"workbuddy": "success" if i < 6 else "already", "traesolo": "success" if i < 6 else "idle"}}
        for i in range(7)
    ],
}
CONFIG = {
    "providers": {
        "workbuddy": {"enabled": True, "mode": "wb", "wb": {"region": "cn", "accessToken": "tok123", "uid": "u1", "domain": "x"}},
        "traesolo": {"enabled": True, "mode": "trae", "trae": {"region": "cn", "token": "eyJabc", "deviceId": "1234567890123456"}},
        "qoder": {"enabled": True, "mode": "qoder", "qoder": {"region": "cn", "token": "", "refreshToken": "drt-abc"}},
        "minimax": {"enabled": True, "mode": "minimax", "minimax": {"accessToken": "mm-tok", "userId": "u9"}},
        "huawei": {"enabled": True, "mode": "huawei", "huawei": {"cookie": "devclouddevuibjJ_SESSION_ID=abc; devclouddevuibtcftk=tk", "portalCookie": ""}},
    },
    "notify": {"enabled": False, "kind": "wecom", "webhook_url": "", "secret": ""},
    "server": {"api_token": "", "api_token_set": False, "local_mode": True},
    "scheduler": {"enabled": True, "times": ["08:30"], "timezone_offset_hours": 8},
}
LOGS = {"logs": [
    {"id": i, "provider": ["workbuddy", "traesolo", "qoder"][i % 3], "day": f"2026-02-{1 + (i % 14):02d}",
     "status": "fail" if i % 9 == 0 else ("already" if i % 7 == 0 else "success"),
     "http_status": 200, "message": "自动签到成功", "points": "+100" if i % 3 == 0 else "",
     "detail": "", "created_at": f"2026-02-{1 + (i % 14):02d} {i % 24:02d}:05:00"}
    for i in range(40)
]}
STATS = {
    "by_provider": {"workbuddy": {"success": 18, "fail": 1}, "traesolo": {"success": 15, "fail": 2}},
    "month_done_days": {"workbuddy": 12, "traesolo": 10},
    "month": "2026-02", "totals": {"total": 40, "success": 33, "fail": 7},
}

errors = []

def handle_api(route):
    url = route.request.url
    if "/api/status" in url: body = STATUS
    elif "/api/config" in url: body = CONFIG
    elif "/api/logs" in url: body = LOGS
    elif "/api/stats" in url: body = STATS
    else: body = {"ok": True}
    route.fulfill(status=200, content_type="application/json", body=json.dumps(body, ensure_ascii=False))

CHECKS_JS = """() => {
  const q = (s) => document.querySelector(s);
  return {
    numCards: q("#numCards") ? q("#numCards").children.length : 0,
    hmCells: q("#heatmap") ? q("#heatmap").querySelectorAll(".hm-cell").length : 0,
    trendPath: !!q("#trendSvg .trend-line"),
    badges: q("#badgeWall") ? q("#badgeWall").children.length : 0,
    yrItems: q("#annualBody") ? q("#annualBody").querySelectorAll(".yr-item").length : 0,
    tokenRows: q("#tokenHealth") ? q("#tokenHealth").querySelectorAll(".th-row").length : 0,
    tokenLogos: q("#tokenHealth") ? q("#tokenHealth").querySelectorAll(".th-ico.has-logo").length : 0,
    tokenPts: q("#tokenHealth") ? q("#tokenHealth").querySelectorAll(".th-pts").length : 0,
    petBtns: !!q("#petFeed") && !!q("#petClean"),
    petName: q("#petCard .pet-name") ? q("#petCard .pet-name").textContent.trim().slice(0, 24) : "",
    themeCells: document.querySelectorAll(".theme-cell").length,
    dataTheme: document.documentElement.getAttribute("data-theme"),
  };
}"""

with sync_playwright() as p:
    srv = serve()
    browser = p.chromium.launch()
    page = browser.new_page()
    page.on("pageerror", lambda e: errors.append("pageerror: " + str(e)))
    page.on("console", lambda m: errors.append("console: " + m.text) if m.type == "error" else None)
    page.route("**/api/**", handle_api)
    page.goto("http://127.0.0.1:18899/index.html")
    page.wait_for_timeout(1500)

    err_flag = page.evaluate("() => window.__signhubErr || ''")

    for v in ["stats", "settings", "logs", "home"]:
        page.evaluate("(n) => window.__signhub.switchView(n, 200, 400)", v)
        page.wait_for_timeout(250)

    page.evaluate("() => window.__signhub.switchView('stats', 200, 400)")
    page.wait_for_timeout(200)
    checks = page.evaluate(CHECKS_JS)

    page.evaluate("() => window.__signhub.feedPet(false)")
    page.wait_for_timeout(300)

    # 真实点击链路回归：页面加载后隐形遮罩不得拦截任何点击
    page.evaluate("() => window.__signhub.switchView('home', 200, 400)")
    page.wait_for_timeout(600)  # 越过 400ms switching 锁
    hit = page.evaluate("""() => {
      const tab = document.querySelector('.tab[data-view="stats"]');
      const r = tab.getBoundingClientRect();
      const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return el ? (el.closest('.tab') ? 'tab' : el.className || el.tagName) : 'none';
    }""")
    onekey_hit = page.evaluate("""() => {
      const b = document.querySelector('#oneKey');
      // 卡片多时 #oneKey 会被推到视口外，elementFromPoint 对屏外坐标返回 null，
      // 于是「命中判定」测的其实是滚动位置而不是遮挡。先滚进来再测。
      b.scrollIntoView({block: 'center'});
      const r = b.getBoundingClientRect();
      const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return el ? (el.closest('#oneKey') ? 'onekey' : el.className || el.tagName) : 'none';
    }""")
    page.click('.tab[data-view="stats"]')
    page.wait_for_timeout(600)
    stats_visible = page.evaluate("() => !document.querySelector('#viewStats').hidden")
    page.click('.tab[data-view="home"]')
    page.wait_for_timeout(600)
    home_visible = page.evaluate("() => !document.querySelector('#viewHome').hidden")
    # 宠物浮标点击应命中浮标本身（而不是隐形遮罩）
    page.evaluate("() => { const f = document.querySelector('#petFloatBtn'); f.hidden = false; }")
    page.wait_for_timeout(100)
    pf = page.evaluate("""() => {
      const f = document.querySelector('#petFloatBtn');
      const r = f.getBoundingClientRect();
      const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return el ? (el.id === 'petFloatBtn' || el.closest('#petFloatBtn') ? 'float' : el.className || el.tagName) : 'none';
    }""")

    page.evaluate("() => window.__signhub.setTheme('light')")
    theme_light = page.evaluate("() => document.documentElement.getAttribute('data-theme')")
    # oled / auto 这两个主题已下线（app.js 的 RETIRED_THEMES），读到就回落星夜。
    # 断言要测的是「回落生效」，不是「还能切到 oled」。
    theme_retired = page.evaluate("() => { window.__signhub.setTheme('oled'); return document.documentElement.getAttribute('data-theme'); }")
    page.evaluate("() => window.__signhub.setTheme('auto')")

    result = {"errFlag": err_flag, "errors": errors[:10], "checks": checks, "hit": hit,
              "onekey_hit": onekey_hit, "stats_visible": stats_visible, "home_visible": home_visible,
              "petfloat_hit": pf, "theme_light": theme_light, "theme_retired": theme_retired}
    print(json.dumps(result, ensure_ascii=False, indent=2))

    # 断言改成「与 stub 数据自洽」而不是写死常量：
    # 以前写死 tokenRows==3 / badges==12，provider 从 3 家变 5 家后
    # 这些断言永远不可能通过，冒烟测试就成了摆设（L23）。
    n_token = checks["tokenRows"]
    ok = (not err_flag and checks["numCards"] == 4 and checks["hmCells"] > 25
          and checks["trendPath"] and checks["badges"] >= 11 and checks["yrItems"] == 4
          # tokenHealth 每家一行，图标与积分布局必须同行数一致
          and n_token == 5 and checks["tokenLogos"] == n_token and checks["tokenPts"] == n_token
          and checks["petBtns"] and checks["themeCells"] >= 5
          and hit == "tab" and onekey_hit == "onekey" and stats_visible and home_visible
          and pf == "float"
          and theme_light == "light" and theme_retired == "dark"
          and not any("pageerror" in e for e in errors))
    print("SMOKE PASS" if ok else "SMOKE FAIL")
    browser.close()
    sys.exit(0 if ok else 1)
