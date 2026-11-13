import Foundation
import TunerCore

/// The copy of the user's data shared between their devices, in iCloud's key-value store. This
/// is simple and needs no setup from the user, but holds at most 1 MB, so the snapshot is compressed.
/// All merging is in the core; this only moves bytes.
final class CloudStore: NSObject, UserDataCloud, ObservableObject {
    @MainActor @Published private(set) var tooLarge = false

    private let store = NSUbiquitousKeyValueStore.default
    private let key = "userdata"
    /// The limit covers the key's name as well; this leaves room for it.
    private let maxBytes = 1_000_000
    private var observer: NSObjectProtocol?

    func observe(changed: @escaping () -> Void, accountChanged: @escaping () -> Void) {
        observer = NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification, object: store, queue: .main
        ) { [weak self] note in
            switch note.userInfo?[NSUbiquitousKeyValueStoreChangeReasonKey] as? Int {
            case NSUbiquitousKeyValueStoreAccountChange:
                accountChanged()
            case NSUbiquitousKeyValueStoreQuotaViolationChange:
                self?.setTooLarge(true)
            default:
                changed()
            }
        }
        store.synchronize()
    }

    func read() -> String? {
        guard let data = store.data(forKey: key),
              let plain = try? (data as NSData).decompressed(using: .lzfse) else { return nil }
        return String(data: plain as Data, encoding: .utf8)
    }

    func write(snapshot: String) -> Bool {
        guard let packed = try? (Data(snapshot.utf8) as NSData).compressed(using: .lzfse) as Data,
              packed.count <= maxBytes else {
            setTooLarge(true)
            return false
        }
        store.set(packed, forKey: key)
        setTooLarge(false)
        return true
    }

    func delete() {
        store.removeObject(forKey: key)
        store.synchronize()
        setTooLarge(false)
    }

    private func setTooLarge(_ value: Bool) {
        Task { @MainActor in if tooLarge != value { tooLarge = value } }
    }
}
