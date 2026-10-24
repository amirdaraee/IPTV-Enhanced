import Foundation
import Security
import TunerCore

/// Keeps the backup copy of the user's own data (playlists with their passwords, favorites,
/// progress, settings) in the Keychain. tvOS may purge Caches, where the database has to live,
/// but never the Keychain, which also survives reinstalling the app.
/// With a paid developer account, an iCloud key-value store can be added next to this so the
/// data also follows the user to other Apple TVs (see docs/plan/IMPLEMENTATION_PLAN.md).
final class KeychainBackup: NSObject, UserDataBackup {
    private let item: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "app.tuner.tv.userdata",
        kSecAttrAccount as String: "snapshot",
    ]

    func save(snapshot: String) {
        let attributes: [String: Any] = [
            kSecValueData as String: Data(snapshot.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        if SecItemUpdate(item as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            SecItemAdd(item.merging(attributes) { $1 } as CFDictionary, nil)
        }
    }

    func load() -> String? {
        var query = item
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
