import Foundation
import Combine

/// 远程后端连接管理。
///
/// ## 为什么不再是「本机跑 Python」
/// 第一版设计是把 backend/ 打包进 App 用 Process 起子进程。这条路在 iOS 上走不通：
/// **Foundation.Process 在 iOS SDK 里标记 unavailable（编译期就失败）**，而且 iOS
/// 沙盒禁止 fork/exec。就算绕过编译，运行期也会被系统杀掉。
/// python-build-standalone 的 aarch64-apple-ios 产物是「链进 App 的静态库」，
/// 不是可 exec 的可执行文件。
///
/// ## 现在的模型
/// App 是**遥控器**：连一台 7x24 跑着 backend/run.py 的机器（电脑 / VPS / 树莓派 /
/// 安卓机 Termux）。签到在那台机器上由 scheduler.py 准点执行 —— 比 iOS 的
/// BGAppRefreshTask（系统决定何时跑，可能三天不跑）可靠得多。
///
/// 首次启动引导用户填「后端地址 + 访问口令」，两者都存 Keychain。
@MainActor
final class BackendEngine: ObservableObject {

    enum State {
        case needsSetup
        case checking
        case ready(URL, String)
        case failed(String)
    }

    @Published private(set) var state: State = .needsSetup

    /// verify() 的世代号：丢弃过期的并发结果（防连点）
    private var verifyGeneration: UInt64 = 0

    /// 等待验证通过的地址 / 口令；验证成功才写 Keychain
    private var pendingAddress: String?
    private var pendingToken: String?

    // MARK: - 持久化键

    // MARK: - 对外

    /// 启动时调用：读已保存的配置并探活一次。
    func restore() {
        guard let addr = KeychainBox.read(account: KeychainKeys.address),
              !addr.isEmpty,
              let token = KeychainBox.read(account: KeychainKeys.token),
              !token.isEmpty else {
            state = .needsSetup
            return
        }
        guard let url = Self.normalize(addr) else {
            state = .failed("后端地址格式不对：\(addr)")
            return
        }
        verify(url: url, token: token)
    }

    /// 用户在设置页填完地址 + 口令后调用。
    func connect(address: String, token: String) {
        let a = address.trimmingCharacters(in: .whitespacesAndNewlines)
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !a.isEmpty else {
            state = .failed("后端地址不能为空")
            return
        }
        guard let url = Self.normalize(a) else {
            state = .failed("地址要写成 http://192.168.1.10:8787 这样（只支持 http / https）")
            return
        }
        pendingAddress = a
        pendingToken = t
        verify(url: url, token: t)
    }

    /// 断开并清除配置（用户主动「退出连接」）。
    func disconnect() {
        KeychainBox.delete(account: KeychainKeys.address)
        KeychainBox.delete(account: KeychainKeys.token)
        state = .needsSetup
    }

    /// 「重试」按钮：优先重试刚才填的那组值，没有再回落到已保存的配置。
    func retry() {
        if let a = pendingAddress, let t = pendingToken, let u = Self.normalize(a) {
            verify(url: u, token: t)
            return
        }
        restore()
    }

    /// 回到设置页重新填。
    func resetToSetup() {
        state = .needsSetup
    }

    // MARK: - 内部

    /// 补全 scheme，去掉尾部斜杠。返回 nil 表示格式不合法。
    static func normalize(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }

        // 先拦非 http(s) scheme。不加这步："ftp://a.com" 会被拼成
        // "http://ftp//a.com"，然后真的去连一个不存在的主机；"http:///path"
        // 会把 path 当成主机名。两者都通过 host 非空检查，用户只会看到 8 秒超时。
        if let r = s.range(of: "://") {
            let scheme = s[..<r.lowerBound].lowercased()
            if !scheme.isEmpty && scheme != "http" && scheme != "https" {
                return nil
            }
        }
        // 本机回环 / 局域网裸 IP 不写 scheme 时补上
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") {
            s = "http://" + s
        }
        while s.hasSuffix("/") { s.removeLast() }
        guard let u = URL(string: s), let host = u.host, !host.isEmpty else { return nil }
        return u
    }

    /// 打一次 /api/ping 确认地址与口令都对。
    private func verify(url: URL, token: String) {
        verifyGeneration &+= 1
        let gen = verifyGeneration
        state = .checking
        var req = URLRequest(url: url.appendingPathComponent("api/ping"))
        req.timeoutInterval = 8
        if !token.isEmpty {
            req.setValue(token, forHTTPHeaderField: "X-API-Token")
        }

        URLSession.shared.dataTask(with: req) { [weak self] data, resp, err in
            Task { @MainActor in
                guard let self, gen == self.verifyGeneration else { return }
                if let err {
                    self.state = .failed(self.describe(err, url: url))
                    return
                }
                guard let http = resp as? HTTPURLResponse else {
                    self.state = .failed("没有收到 HTTP 响应")
                    return
                }
                switch http.statusCode {
                case 200:
                    // 只有验证通过才落盘：先写后验会让错口令留在 Keychain 里，
                    // 「重试」读回同一个错口令 → 永远 401 的死循环。
                    if let a = self.pendingAddress, let t = self.pendingToken {
                        KeychainBox.write(a, account: KeychainKeys.address)
                        KeychainBox.write(t, account: KeychainKeys.token)
                    }
                    self.pendingAddress = nil
                    self.pendingToken = nil
                    self.state = .ready(url, token)
                case 401:
                    self.state = .failed(Self.hint401(data))
                case 403:
                    self.state = .failed("被后端拒绝（403）。后端只允许本机 / 局域网访问，确认两台设备在同一网络。")
                case 429:
                    self.state = .failed("尝试次数太多（429）。限流按设备 IP 记，不区分对口令还是错口令 ——\n先等 60 秒再点重试；如果确认口令是对的，等一下就能进。")
                default:
                    let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                    self.state = .failed("HTTP \(http.statusCode)：\(body.prefix(200))")
                }
            }
        }.resume()
    }

    /// 401 有两种原因，后端会在 body 里用 hint 区分（见 server._ensure_api_token）：
    /// ① 口令填错了；② 后端根本没能生成口令（数据目录不可写）→ 那时任何口令都进不去。
    /// 只按状态码提示会让用户在后端不可用时一直重抄口令。
    private static func hint401(_ data: Data?) -> String {
        let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        if body.contains("未设置访问口令") || body.contains("仅本机可用") {
            return "后端没有可用的访问口令（启动时没能写进配置文件）。\n"
                + "去那台机器看启动横幅：如果打的是「无法自动生成」，手动在它的 data/config.json 里填 server.api_token 再重启。"
        }
        return "口令不对（401）。去后端启动横幅里抄那个口令，或在那台机器的「设置 - 访问口令」里改成你记得的。"
    }

    private func describe(_ err: Error, url: URL) -> String {
        let ns = err as NSError
        let host = url.host ?? ""
        switch ns.code {
        case NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost:
            return "连不上 \(url.absoluteString)。\n确认那台机器上 python3 run.py 正在跑、防火墙放行了 8787。"
        case NSURLErrorTimedOut:
            return "连接超时。\n地址能通但没响应，多半是 IP 写错或不在同一局域网。"
        case NSURLErrorNotConnectedToInternet:
            return "iPhone 没联网。"
        case NSURLErrorCannotFindHost:
            return "解析不到主机 \(host)。局域网请直接填 IP。"
        case NSURLErrorAppTransportSecurityRequiresSecureConnection:
            return "被 iOS 的 ATS 拦了：明文 http 只放行本机地址、.local 域名和局域网 IP。\\n公网地址（VPS 之类）请给后端配 https 反代。"
        default:
            return "\(ns.localizedDescription)（\(ns.code)）"
        }
    }
}
