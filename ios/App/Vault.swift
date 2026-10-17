import Foundation
import Security
import WifeLink

/// What the phone keeps (the link key, the computer's addresses, her card) in the Keychain: encrypted by iOS, kept on
/// this phone only (not in iCloud or backups), and nothing in the app shows the link key.
final class Vault: Keep {
    private let item: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                       kSecAttrService as String: "io.wife.mobile", kSecAttrAccount as String: "link"]
    private let lock = NSRecursiveLock()

    func load() -> [String: Any] {
        lock.lock()
        defer { lock.unlock() }
        var query = item
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &found) == errSecSuccess, let data = found as? Data else { return [:] }
        return JSON.object(data) ?? [:]
    }

    func update(_ change: (inout [String: Any]) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        var state = load()
        change(&state)
        let data = JSON.data(state)
        if SecItemUpdate(item as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecItemNotFound {
            var add = item
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}
