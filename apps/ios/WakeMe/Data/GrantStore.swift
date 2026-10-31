import Foundation
import WakeMeAPI
import WakeMeCore

/// The grants this person gave to friends, cached on the phone so an incoming request is
/// re-validated here against the recipient's own latest rules, even offline (mirrors Android's
/// grants table). A revoked or narrowed grant wins over a request the server already sent. Also
/// remembers which requests were handled, so a repeated push never plays twice.
actor GrantStore {
    static let shared = GrantStore()

    private let grantsKey = "grants.given"
    private let processedKey = "commands.processed"
    private var refreshing: Task<Void, Never>?

    /// The live grant from me to this friend, if any.
    func grant(from grantorId: String, to granteeId: String) -> GrantSnapshot? {
        load().first { $0.grantorId == grantorId && $0.granteeId == granteeId && $0.revokedAt == nil }
    }

    /// Re-fetches every grant I gave (revoked ones too, so a revocation replaces the old copy).
    func refresh(api: WakeMeAPI) async {
        if let running = refreshing { return await running.value }
        let task = Task {
            if let grants = try? await api.grants(direction: "given") { self.save(grants.map(\.snapshot)) }
        }
        refreshing = task
        await task.value
        refreshing = nil
    }

    /// Local revocation first: the cached copy stops authorizing before the server hears.
    func markRevoked(granteeId: String) {
        let now = Instant.format(Date())
        save(load().map { g in
            var g = g
            if g.granteeId == granteeId && g.revokedAt == nil { g.revokedAt = now }
            return g
        })
    }

    /// True the first time a request id is seen; later deliveries of the same request are duplicates.
    func firstTime(_ commandId: String) -> Bool {
        var seen = UserDefaults.standard.stringArray(forKey: processedKey) ?? []
        guard !seen.contains(commandId) else { return false }
        seen.append(commandId)
        UserDefaults.standard.set(Array(seen.suffix(500)), forKey: processedKey)
        return true
    }

    func wasProcessed(_ commandId: String) -> Bool {
        (UserDefaults.standard.stringArray(forKey: processedKey) ?? []).contains(commandId)
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: grantsKey)
        UserDefaults.standard.removeObject(forKey: processedKey)
    }

    private func load() -> [GrantSnapshot] {
        UserDefaults.standard.data(forKey: grantsKey).flatMap { try? JSONDecoder().decode([GrantSnapshot].self, from: $0) } ?? []
    }

    private func save(_ grants: [GrantSnapshot]) {
        UserDefaults.standard.set(try? JSONEncoder().encode(grants), forKey: grantsKey)
    }
}
