import Foundation
import WakeMeAPI
import WakeMeCore

/// Personal wake-ups set before signing in (doc 02 §9 guest alarms, doc 03 §5 step 2), mirroring
/// Android's LocalRoutines. They are stored and armed with AlarmKit only on this phone, with no
/// server and no receipts. Signing in moves them to the account: each is created on the server
/// first and only then removed here, so an offline sign-in keeps the local copy ringing.
final class LocalRoutines: @unchecked Sendable {
    static let shared = LocalRoutines()
    private let key = "local.routines"
    private let lock = NSLock()

    func all() -> [InstalledRoutine] {
        lock.lock()
        defer { lock.unlock() }
        return load()
    }

    /// Saves and arms a wake-up on this phone; returns what AlarmKit accepted.
    func save(_ terms: WakeMeAPI.PlanTerms) async -> InstallResult {
        let routine = InstalledRoutine(
            planId: localPlanPrefix + UUID().uuidString.lowercased(), version: 1, label: terms.label,
            localTime: terms.localTime, timeZone: terms.timeZone, timeZonePolicy: terms.timeZonePolicy,
            recurrence: terms.recurrence, soundId: terms.soundId, ringSeconds: terms.ringSeconds,
            challengeRevisionId: nil, followUpDelaySeconds: nil, paused: false // challenges come from the account
        )
        update { $0.append(routine) }
        return await WakeAlarms.shared.install(routine, label: routine.label)
    }

    /// Local first: it stops ringing at once.
    func delete(_ planId: String) {
        guard isLocalPlan(planId) else { return }
        WakeAlarms.shared.cancel(planId: planId)
        update { $0.removeAll { $0.planId == planId } }
    }

    /// Arms saved wake-ups that aren't armed yet (alarm access was granted after saving).
    func armMissing() async {
        guard WakeAlarms.shared.authorization == .authorized else { return }
        for r in all() where WakeAlarms.shared.armedRoutine(planId: r.planId) == nil {
            _ = await WakeAlarms.shared.install(r, label: r.label)
        }
    }

    /// After signing in: creates each local wake-up as the person's own routine, then removes the
    /// local copy (sync installs the server version). Stops at the first failure and keeps the
    /// rest, so nothing stops ringing; tried again at the next signed-in start.
    @discardableResult
    func moveToAccount(api: WakeMeAPI) async -> Int {
        var moved = 0
        for r in all() {
            let terms = WakeMeAPI.PlanTerms(label: r.label, localTime: r.localTime, timeZone: r.timeZone, timeZonePolicy: r.timeZonePolicy,
                                            recurrence: r.recurrence, soundId: r.soundId, ringSeconds: r.ringSeconds)
            guard (try? await api.createPlan(terms)) != nil else { return moved }
            delete(r.planId)
            moved += 1
        }
        return moved
    }

    func clear() {
        for r in all() { WakeAlarms.shared.cancel(planId: r.planId) }
        update { $0.removeAll() }
    }

    private func load() -> [InstalledRoutine] {
        UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode([InstalledRoutine].self, from: $0) } ?? []
    }

    private func update(_ change: (inout [InstalledRoutine]) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        var list = load()
        change(&list)
        UserDefaults.standard.set(try? JSONEncoder().encode(list), forKey: key)
    }
}
