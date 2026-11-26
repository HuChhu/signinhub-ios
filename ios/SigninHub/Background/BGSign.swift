import Foundation
import BackgroundTasks
import UIKit

/// 后台任务：**不是**在本机签到，而是「唤醒时 ping 一下后端，把还没签的提醒出来」。
///
/// ## 为什么不做本机签到
/// iOS 没有 AlarmManager 的等价物。BGAppRefreshTask 何时执行由系统决定
/// （常见「昨天没跑」「隔两天跑一次」），App 被用户划掉后完全不跑。
/// 真签到交给那台常驻机器上的 backend/scheduler.py，它才准点。
///
/// ## 注册时机（P0）
/// BGTaskScheduler.register 必须在 didFinishLaunching 返回前完成，
/// 晚于这个时机系统会抛 NSInternalInconsistencyException —— 这是崩溃，不是不生效。
/// 所以走 AppDelegate 的 didFinishLaunching，不从 SwiftUI 的 .task 里调。
enum BGSign {
    static let taskID = "app.signhub.tick"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskID, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            scheduleNext()

            // 超时前必须 setTaskCompleted；超过系统预算再调会抛
            // 「A background task was completed after expiration」。
            // iOS 通常给 ~30s，这里 20s 就主动收尾。
            // 锁内只置标志，锁外调 setTaskCompleted ——
            // NSLock 不可重入，持锁调系统回调（会释放 BGTask）有自死锁风险。
            var finished = false
            let lock = NSLock()
            func finish(_ ok: Bool) {
                lock.lock()
                if finished { lock.unlock(); return }
                finished = true
                lock.unlock()
                refresh.setTaskCompleted(success: ok)
            }

            refresh.expirationHandler = { finish(false) }

            // 真超时兜底。iOS 通常给 ~25s，22s 收尾。
            // 必须是「兜底」而不是「主路径」：原来写 15s，而 tick 的网络超时是 20s，
            // 网络稍慢就先被兜底判失败 —— 后台探活几乎永远报 false，
            // 系统会因此降低本 App 的调度频率。现在兜底 22s、网络 12s，正常情况下
            // 永远是 tick 先返回。
            DispatchQueue.main.asyncAfter(deadline: .now() + 22) { finish(false) }

            Task {
                let ok = await tick()
                finish(ok)
            }
        }
    }

    static func scheduleNext() {
        let req = BGAppRefreshTaskRequest(identifier: taskID)
        // 「最早」不是「一定」，系统可能更晚。给 4 小时。
        req.earliestBeginDate = Date(timeIntervalSinceNow: 4 * 3600)
        do {
            try BGTaskScheduler.shared.submit(req)
        } catch {
            // TooManyPendingTaskRequests 是正常的（队列里已经有一个），不刷屏
            NSLog("[BGSign] submit 失败: \(error.localizedDescription)")
        }
    }

    /// 读 Keychain 里保存的后端地址，打一次 /api/status。
    /// 只用来判断「后端还活着吗」，不做签到。
    static func tick() async -> Bool {
        guard let addr = KeychainBox.read(account: KeychainKeys.address),
              let token = KeychainBox.read(account: KeychainKeys.token),
              let base = normalize(addr) else {
            return false
        }
        var req = URLRequest(url: base.appendingPathComponent("api/status"))
        req.setValue(token, forHTTPHeaderField: "X-API-Token")
        req.timeoutInterval = 12

        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            // BGAppRefreshTask 的 success 语义是「这次刷新完成了吗」，不是「业务成功了吗」。
            // 401 / 500 也算完成 —— 返回 false 会让系统降低本 App 的调度频率，
            // 口令换过一次之后就再也唤醒不了了。
            return resp is HTTPURLResponse
        } catch {
            return false
        }
    }

    private static func normalize(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") {
            s = "http://" + s
        }
        while s.hasSuffix("/") { s.removeLast() }
        guard let u = URL(string: s), u.host != nil else { return nil }
        return u
    }
}

/// BGTaskScheduler.register 必须发生在 didFinishLaunching 返回之前，
/// SwiftUI 的 .task / onAppear 都太晚。用 delegate adaptor 把时机钉死。
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        BGSign.register()
        BGSign.scheduleNext()
        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // 每次进后台重排一次，保证队列里总有一个待执行的请求
        BGSign.scheduleNext()
    }
}
