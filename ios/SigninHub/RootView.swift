import SwiftUI

/// 根视图：有保存的连接配置就直接进 WebView，否则显示连接设置页。
struct RootView: View {
    @EnvironmentObject private var engine: BackendEngine
    @State private var didRestore = false

    var body: some View {
        ZStack {
            Color(red: 0.04, green: 0.04, blue: 0.06).ignoresSafeArea()

            switch engine.state {
            case .needsSetup:
                SetupView()
            case .checking:
                LoadingScreen(title: "正在连接后端", detail: "探活中…")
            case .ready(let url, let token):
                WebShell(url: url, token: token)
                    .ignoresSafeArea(edges: .bottom)
            case .failed(let msg):
                ErrorScreen(message: msg)
            }
        }
        // 只在首次出现时恢复，避免 body 重建时反复触发。
        .onAppear {
            guard !didRestore else { return }
            didRestore = true
            engine.restore()
        }
    }
}

/// iOS 15 没有 NavigationStack，用条件编译兜住：
/// · 16+ 用 NavigationStack（新 API，行为正确）
/// · 15 用 NavigationView（老 API，够用）
/// 两者都用 navigationTitle / toolbar，调用处不用分支。
struct CompatNavigation<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        if #available(iOS 16.0, *) {
            NavigationStack { content() }
        } else {
            NavigationView { content() }
                .navigationViewStyle(.stack)
        }
    }
}

// MARK: - 设置页

struct SetupView: View {
    @EnvironmentObject private var engine: BackendEngine

    @State private var address: String = KeychainBox.read(account: KeychainKeys.address) ?? ""
    @State private var token: String = ""
    @State private var showHelp = false

    /// 正在连接时禁用按钮：verify() 有世代号防串扰，但连点会让界面在
    /// checking/failed 之间闪，用户以为没反应。
    private var busy: Bool {
        if case .checking = engine.state { return true }
        return false
    }

    var body: some View {
        CompatNavigation {
            Form {
                Section {
                    TextField("http://192.168.1.10:8787", text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text("后端地址")
                } footer: {
                    Text("填那台跑着 `python3 run.py` 的机器的地址。手机和它要在同一局域网。")
                }

                Section {
                    SecureField("启动横幅里打印的那个口令", text: $token)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("访问口令")
                } footer: {
                    Text("后端首次启动会自动生成并打印一次。忘了可以在电脑上「设置 - 访问口令」里看或改。")
                }

                Section {
                    Button {
                        engine.connect(address: address, token: token)
                    } label: {
                        HStack {
                            Spacer()
                            Text("连接").bold()
                            Spacer()
                        }
                    }
                    .disabled(address.isEmpty || busy)

                    Button("怎么拿到地址和口令？") {
                        showHelp = true
                    }
                }
            }
            .navigationTitle("连接后端")
            .sheet(isPresented: $showHelp) {
                HelpSheet()
            }
        }
    }
}

private struct HelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        CompatNavigation {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    step("1", "在电脑 / VPS / 树莓派 / 安卓 Termux 上跑后端")
                    code("cd backend\npython3 run.py")

                    step("2", "看启动横幅")
                    Text("它会打印本机控制台、局域网地址，以及一个一次性的访问口令。")
                        .font(.footnote).foregroundStyle(.secondary)

                    step("3", "把地址和口令填进来")
                    Text("地址用横幅里「手机访问(局域网)」那一行。")
                        .font(.footnote).foregroundStyle(.secondary)

                    Divider().padding(.vertical, 6)

                    Text("为什么 iPhone 上不自己签到")
                        .font(.headline)
                    Text("iOS 不允许 App 起子进程，也没有能准点触发的定时 API。BGAppRefreshTask 何时执行由系统决定，可能几天不跑，App 被划掉后完全不跑。所以签到交给那台常开的机器，它才准点。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding(20)
            }
            .navigationTitle("帮助")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("知道了") { dismiss() }
                }
            }
        }
    }

    private func step(_ n: String, _ t: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(n)
                .font(.system(.caption, design: .rounded).bold())
                .frame(width: 22, height: 22)
                .background(.tint, in: Circle())
                .foregroundStyle(.white)
            Text(t).font(.subheadline).bold()
        }
    }

    private func code(_ s: String) -> some View {
        Text(s)
            .font(.system(.caption, design: .monospaced))
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 8))
            .textSelection(.enabled)
    }
}

// MARK: - 状态屏

private struct LoadingScreen: View {
    let title: String
    let detail: String
    @State private var spin = false

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 52))
                .foregroundStyle(.linearGradient(colors: [.cyan, .blue],
                                                 startPoint: .topLeading,
                                                 endPoint: .bottomTrailing))
                .rotationEffect(.degrees(spin ? 360 : 0))
                .animation(.linear(duration: 2.4).repeatForever(autoreverses: false), value: spin)
            Text(title).font(.headline)
            Text(detail).font(.footnote).foregroundStyle(.secondary)
        }
        .onAppear { spin = true }
    }
}

private struct ErrorScreen: View {
    let message: String
    @EnvironmentObject private var engine: BackendEngine

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 44))
                .foregroundStyle(.orange)
            Text("连不上").font(.headline)

            ScrollView {
                Text(message)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: 220)
            .padding(12)
            .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12))

            HStack(spacing: 12) {
                Button("改了地址，重新连") { engine.resetToSetup() }
                    .buttonStyle(.borderedProminent)
                Button("重试") { engine.retry() }
                    .buttonStyle(.bordered)
            }
        }
        .padding(24)
    }
}
