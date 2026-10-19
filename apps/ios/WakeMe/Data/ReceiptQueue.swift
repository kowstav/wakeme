import Foundation
import WakeMeAPI

/// Wake-session outcomes wait here until the server confirms them, so a session finished
/// offline is still reported later. Outcomes only move forward on the server, so a retry is
/// harmless. Mirrors Android's ReceiptQueue for occurrence receipts.
actor ReceiptQueue {
    static let shared = ReceiptQueue()

    struct Occurrence: Codable, Equatable, Sendable {
        let planId: String
        let occurrenceKey: String
        let version: Int
        let outcome: String
    }

    private let key = "receipts.occurrences"
    private var flushing = false

    func enqueue(_ receipt: Occurrence, api: WakeMeAPI) async {
        var pending = load()
        if !pending.contains(receipt) { pending.append(receipt) }
        save(pending)
        await flush(api: api)
    }

    /// Sends what's waiting, oldest first; stops at the first network failure and keeps the rest.
    func flush(api: WakeMeAPI) async {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        for receipt in load() {
            do {
                try await api.reportOccurrence(planId: receipt.planId, occurrenceKey: receipt.occurrenceKey, version: receipt.version, outcome: receipt.outcome)
                remove(receipt)
            } catch let e as APIError where (400..<500).contains(e.status) && e.status != 401 && e.status != 429 {
                remove(receipt) // no longer applicable (routine gone, account changed)
            } catch {
                return
            }
        }
    }

    func clear() { UserDefaults.standard.removeObject(forKey: key) }

    private func load() -> [Occurrence] {
        UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode([Occurrence].self, from: $0) } ?? []
    }

    private func save(_ list: [Occurrence]) {
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: key)
    }

    private func remove(_ receipt: Occurrence) {
        save(load().filter { $0 != receipt })
    }
}
