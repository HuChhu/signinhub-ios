import Foundation
import Security

/// iOS 侧凭据存储。对应 Android 的 SecretBox（Keystore + AES/GCM）。
///
/// iOS 不需要自己管 AES：Keychain 本身就是硬件保护的 KV 存储。
///
/// ## kSecAttrAccessible 选 AfterFirstUnlockThisDeviceOnly
/// 第一版用的是 WhenUnlockedThisDeviceOnly —— 那条**锁屏时读不到**。
/// 而 BGAppRefreshTask 恰好主要在锁屏状态下唤醒，等于后台永远拿不到口令。
/// AfterFirstUnlock 只要求「开机后解锁过一次」，之后锁屏也能读；
/// ThisDeviceOnly 保证不随 iCloud 备份跑到别的设备上。
///
/// ## 写入用 update-then-add
/// SecItemDelete + SecItemAdd 两步非原子：并发时（主线程写、后台读）
/// 中间那个窗口里条目是不存在的，读方会拿到 nil。
/// 改成先 SecItemUpdate，失败（errSecItemNotFound）再 SecItemAdd。

/// Keychain 里用到的 account 名。
/// BackendEngine 和 BGSign 都要读，必须是同一份常量 ----
/// 之前两处各抄一遍字符串，改 key 名时编译期不报错、运行时静默失败。
enum KeychainKeys {
    static let address = "backend_address"
    static let token = "api_token"
}

/// 凭据读写封装。
enum KeychainBox {

    private static let service = "app.signhub.ios"

    private static func baseQuery(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    @discardableResult
    static func write(_ value: String, account: String) -> Bool {
        let data = Data(value.utf8)
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        // 先尝试更新
        let updateStatus = SecItemUpdate(baseQuery(account) as CFDictionary,
                                         attrs as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        guard updateStatus == errSecItemNotFound else {
            NSLog("[KeychainBox] update 失败 \(account): \(updateStatus)")
            return false
        }

        // 不存在才新增
        var add = baseQuery(account)
        add.merge(attrs) { _, new in new }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        if addStatus != errSecSuccess {
            NSLog("[KeychainBox] add 失败 \(account): \(addStatus)")
            return false
        }
        return true
    }

    static func read(account: String) -> String? {
        var q = baseQuery(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne

        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        guard status == errSecSuccess, let d = out as? Data else {
            if status != errSecItemNotFound {
                NSLog("[KeychainBox] read 失败 \(account): \(status)")
            }
            return nil
        }
        return String(data: d, encoding: .utf8)
    }

    static func delete(account: String) {
        SecItemDelete(baseQuery(account) as CFDictionary)
    }
}
