import Foundation
import WakeMeAPI
import WakeMeCore

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

    /// What this phone did with a friend's request; sent in order, same retry rules.
    struct Command: Codable, Equatable, Sendable {
        let commandId: String
        let kind: String
        let reason: String?
        let at: Date
    }

    private let key = "receipts.occurrences"
    private let commandsKey = "receipts.commands"
    private var flushing = false

    func enqueue(_ receipt: Occurrence, api: WakeMeAPI) async {
        record(receipt)
        await flush(api: api)
    }

    /// Keeps the outcome for the next flush without touching the network.
    func record(_ receipt: Occurrence) {
        guard !isLocalPlan(receipt.planId) else { return } // guest wake-ups exist only on this phone
        var pending = load()
        if !pending.contains(receipt) { pending.append(receipt) }
        save(pending)
    }

    /// Sends what's waiting, oldest first; stops at the first network failure and keeps the rest.
    func flush(api: WakeMeAPI) async {
        guard !flushing else { return }
        flushing = true
        defer { flushing = false }
        for receipt in loadCommands() {
            do {
                _ = try await api.postCommandReceipt(receipt.commandId, kind: receipt.kind, reason: receipt.reason, at: receipt.at)
                saveCommands(loadCommands().filter { $0 != receipt })
            } catch let e as APIError where (400..<500).contains(e.status) && e.status != 401 && e.status != 429 {
                saveCommands(loadCommands().filter { $0 != receipt }) // the request is gone or no longer this phone's
            } catch {
                return
            }
        }
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

    func enqueue(_ receipt: Command, api: WakeMeAPI) async {
        var pending = loadCommands()
        if !pending.contains(receipt) { pending.append(receipt) }
        saveCommands(Array(pending.suffix(200)))
        await flush(api: api)
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: key)
        UserDefaults.standard.removeObject(forKey: commandsKey)
    }

    private func loadCommands() -> [Command] {
        UserDefaults.standard.data(forKey: commandsKey).flatMap { try? JSONDecoder().decode([Command].self, from: $0) } ?? []
    }

    private func saveCommands(_ list: [Command]) {
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: commandsKey)
    }

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
