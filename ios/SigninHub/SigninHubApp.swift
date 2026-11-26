import SwiftUI

@main
struct SigninHubApp: App {
    // BGTaskScheduler.register 必须在 didFinishLaunching 返回前完成。
    // SwiftUI 的 .task / onAppear 都晚于这个时机，晚注册会让系统抛
    // NSInternalInconsistencyException（崩溃，不是静默失效）。
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    @StateObject private var engine = BackendEngine()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(engine)
                .preferredColorScheme(.dark)
        }
    }
}
