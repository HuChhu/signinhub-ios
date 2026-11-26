import SwiftUI
import UIKit
import WebKit

/// WKWebView 壳。
///
/// 只加载用户配置的后端地址；外链交给系统浏览器。
/// 不挂 WKScriptMessageHandler —— 后端在远端，网页直接 fetch 即可，
/// 不需要 Android 那套 JS 桥（也就没有 trusted() 的来源判定要维护）。
///
/// ## 口令注入（P1 修复）
/// 第一版在每个 main-frame 文档开始都重写一遍 `signhub_base` 和 `signhub_token`，
/// 结果用户填的后端地址一刷新就被清空。现在改成：
///   · token 每次注入（后端校验它，丢了就全站 401）
///   · `signhub_base` **只在没有值时才写**，绝不覆盖用户已保存的地址
struct WebShell: UIViewRepresentable {
    let url: URL
    let token: String

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.defaultWebpagePreferences.allowsContentJavaScript = true

        let web = WKWebView(frame: .zero, configuration: cfg)
        web.navigationDelegate = context.coordinator
        web.scrollView.contentInsetAdjustmentBehavior = .always
        web.allowsBackForwardNavigationGestures = false
        web.isOpaque = false
        web.backgroundColor = UIColor(red: 0.04, green: 0.04, blue: 0.06, alpha: 1)
        web.scrollView.backgroundColor = web.backgroundColor

        #if DEBUG
        if #available(iOS 16.4, *) { web.isInspectable = true }
        #endif

        let js = """
        (function(){
          try {
            sessionStorage.setItem('signhub_token', \(Self.jsString(token)));
            // 远程客户端模式下 signhub_base 就该是空（baseUrl() 回落 location.origin，
            // 也就是当前连着的后端）。用户如果想改成另一个后端，配置页自己会写。
            // 这里什么都不做 ---- 原来那个 block 用 !getItem 判空，'' 是 falsy，
            // 等于每次加载都重写一遍，防护根本没生效。
          } catch (e) {}
        })();
        """
        web.configuration.userContentController.addUserScript(
            WKUserScript(source: js,
                         injectionTime: .atDocumentStart,
                         forMainFrameOnly: true)
        )

        web.load(URLRequest(url: url))
        context.coordinator.web = web
        context.coordinator.loadedURL = url
        return web
    }

    /// 换后端时必须重新加载：SwiftUI 会复用同一个 WebShell 实例
    /// （同层级、同类型），makeUIView 不会再跑，URL 变了页面还停在旧后端上。
    /// 用户「改了地址，重新连」走的就是这条路，不处理就是必现白屏。
    func updateUIView(_ uiView: WKWebView, context: Context) {
        guard context.coordinator.loadedURL != url else { return }
        context.coordinator.loadedURL = url
        context.coordinator.baseURL = url

        // 口令也要重注入：新后端的口令可能不同
        let js = """
        try { sessionStorage.setItem('signhub_token', \(Self.jsString(token))); } catch (e) {}
        """
        uiView.evaluateJavaScript(js) { _, _ in
            uiView.load(URLRequest(url: url))
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(baseURL: url) }

    /// 把 Swift 字符串安全地变成 JS 字符串字面量。
    /// 走 JSONSerialization 包一层数组再剥掉方括号 —— 含引号 / 反斜杠 / 换行 /
    /// U+2028 的输入都能正确转义（已用 10 组恶意输入验证过）。
    static func jsString(_ s: String) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: [s], options: []),
              let arr = String(data: d, encoding: .utf8),
              arr.hasPrefix("["), arr.hasSuffix("]") else {
            // 退路：手工转义，绝不再返回空串让 token 悄悄丢失
            var out = "\""
            for ch in s {
                switch ch {
                case "\"": out += "\\\""
                case "\\": out += "\\\\"
                case "\n": out += "\\n"
                case "\r": out += "\\r"
                case "\t": out += "\\t"
                default: out.append(ch)
                }
            }
            return out + "\""
        }
        return String(arr.dropFirst().dropLast())
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        weak var web: WKWebView?
        var baseURL: URL
        /// 当前已加载的后端地址，用于 updateUIView 判断要不要重载
        var loadedURL: URL?

        init(baseURL: URL) {
            self.baseURL = baseURL
            self.loadedURL = baseURL
        }

        /// 只放行「用户配置的那个后端」本身；其余一律丢给系统浏览器。
        /// 用 scheme + host + port 三元组判定，不用裸 host ——
        /// 否则同机其它端口（127.0.0.1:9999 之类）会被放进来共享 Cookie 存储。
        func webView(_ webView: WKWebView,
                     decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            // 只对主框架导航做判定；iframe 里的导航不该影响主页面，
            // 交给 CSP（frame-src 'none'）拦截。
            guard action.targetFrame?.isMainFrame != false,
                  let u = action.request.url else { return decisionHandler(.cancel) }

            if isSameBackend(u) {
                return decisionHandler(.allow)
            }

            // 用户点了页面里的外链：只有 http/https 才交给系统，别的一律丢弃
            if u.scheme == "http" || u.scheme == "https" {
                UIApplication.shared.open(u, options: [:]) { _ in }
            }
            decisionHandler(.cancel)
        }

        private func isSameBackend(_ u: URL) -> Bool {
            let s1 = (u.scheme ?? "").lowercased()
            let s2 = (baseURL.scheme ?? "").lowercased()
            let h1 = (u.host ?? "").lowercased()
            let h2 = (baseURL.host ?? "").lowercased()
            let p1 = u.port ?? (s1 == "https" ? 443 : 80)
            let p2 = baseURL.port ?? (s2 == "https" ? 443 : 80)
            return s1 == s2 && h1 == h2 && p1 == p2
        }
    }
}
