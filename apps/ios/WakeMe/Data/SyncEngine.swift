import BackgroundTasks
import Foundation
import Observation
import WakeMeAPI
import WakeMeCore

extension WakeMeAPI {
    /// The app's API: tokens from Session, signatures from DeviceKey. Shared by screens,
    /// sync and alarm intents.
    static let app = WakeMeAPI(client: APIClient(baseURL: AppConfig.apiBaseURL, tokens: Session.shared, signer: DeviceKey.shared))
}

/// Converges this phone with the server, as Android's SyncEngine does. The cursor feed is the
/// source of truth (push is only a hint), so a phone that missed
/// changes catches up when the app opens or iOS grants it background time. Applying a routine
/// arms it with AlarmKit on the primary phone and reports exactly what AlarmKit accepted.
/// Everything that installs or tops up alarms runs here, one operation at a time.
@MainActor
@Observable
final class SyncEngine {
    static let shared = SyncEngine()
    /// Also listed in Info.plist (BGTaskSchedulerPermittedIdentifiers).
    nonisolated static let refreshTaskId = "app.wakeme.ios.sync"

    /// Changes when routines on this phone changed, for the Routines screen to reload.
    private(set) var routinesRevision = 0

    private let defaults = UserDefaults.standard
    private let cursorKey = "sync.cursor"
    /// Set when a receipt couldn't be sent: the next sync re-checks every routine.
    private let reconcileKey = "sync.reconcile"
    @ObservationIgnored private var tail: Task<Void, Never>?
    /// Bumped at sign-out: work that started before it must not leave anything armed.
    @ObservationIgnored private var epoch = 0

    /// Pulls changes since the last cursor (or re-fetches everything on first run or reset).
    func sync(_ app: AppModel) async {
        await serially { await self.run(app) }
    }

    /// Re-checks the given routines against this phone, as the Routines screen lists them;
    /// routines it doesn't list stop ringing here. Returns true when a receipt was sent.
    func reconcile(_ mine: [PlanDTO], app: AppModel) async -> Bool {
        await serially {
            let primary = await app.isPrimaryPhone()
            return await self.reconcileAll(mine, primary: primary, api: app.api)
        }
    }

    /// Local top-up of every armed routine, then reports; for alarm intents and background time.
    func topUp() async {
        await serially {
            await self.topUpAll(api: .app)
            await ReceiptQueue.shared.flush(api: .app)
        }
    }

    /// Background app refresh: whatever iOS allows, never used as an alarm clock.
    func backgroundRefresh(_ app: AppModel) async {
        Self.scheduleRefresh()
        await app.prepareForBackgroundWork()
        await sync(app)
    }

    /// Sign-out, before alarms are cancelled: forget the cursor so another account starts from
    /// a full fetch, and stop work already in flight from arming anything.
    func reset() {
        epoch += 1
        defaults.removeObject(forKey: cursorKey)
        defaults.removeObject(forKey: reconcileKey)
    }

    /// Asks iOS for background time in about an hour; iOS decides when, if at all.
    nonisolated static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTaskId)
        request.earliestBeginDate = Date().addingTimeInterval(60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    // MARK: - running one operation at a time

    private func serially<T: Sendable>(_ work: @escaping @MainActor @Sendable () async -> T) async -> T {
        let previous = tail
        let task = Task { @MainActor in
            await previous?.value
            return await work()
        }
        tail = Task { _ = await task.value }
        return await task.value
    }

    private func run(_ app: AppModel) async {
        // Local first: routines stay armed over the window even when the server can't be reached.
        await topUpAll(api: app.api)
        guard await Session.shared.isSignedIn, app.me != nil else { return }
        await ReceiptQueue.shared.flush(api: app.api)
        do {
            guard let cursor = defaults.string(forKey: cursorKey), !defaults.bool(forKey: reconcileKey) else {
                try await bootstrap(app)
                return
            }
            var next = cursor
            var changed = false
            while true {
                let page = try await app.api.sync(cursor: next)
                if page.resetRequired == true {
                    try await bootstrap(app)
                    return
                }
                for change in page.changes {
                    if try await apply(change, app: app) { changed = true }
                }
                next = page.cursor
                defaults.set(next, forKey: cursorKey)
                if !page.hasMore { break }
            }
            if changed { routinesRevision += 1 }
        } catch {
            // Offline or the server is unavailable: armed alarms keep ringing; the next sync retries.
        }
    }

    /// First sync, a reset, or a receipt that didn't arrive: every routine from its list.
    private func bootstrap(_ app: AppModel) async throws {
        let head = try await app.api.sync(cursor: nil).cursor
        await app.refreshMe()
        await GrantStore.shared.refresh(api: app.api)
        let mine = try await app.api.plans().mine
        defaults.set(false, forKey: reconcileKey)
        let primary = await app.isPrimaryPhone()
        _ = await reconcileAll(mine, primary: primary, api: app.api)
        defaults.set(head, forKey: cursorKey)
        routinesRevision += 1
    }

    /// Returns true when routines on this phone may have changed.
    private func apply(_ change: SyncChangeDTO, app: AppModel) async throws -> Bool {
        switch change.entity {
        case "me":
            guard let me = change.me else { return false }
            let wasPrimary = await app.isPrimaryPhone()
            app.updateMe(me)
            // The primary phone changed: move the alarms so exactly one phone rings (ADR 0007).
            let primary = await app.isPrimaryPhone()
            guard primary != wasPrimary else { return false }
            let mine = try await app.api.plans().mine
            _ = await reconcileAll(mine, primary: primary, api: app.api)
            return true
        case "grant":
            // Requests are re-validated against this cache; refresh it whenever a grant changes.
            await GrantStore.shared.refresh(api: app.api)
            return false
        case "alarm_plan":
            guard let me = app.me else { return false }
            guard let plan = change.plan else {
                // No longer visible to this account: it must not ring here.
                WakeAlarms.shared.cancel(planId: change.id)
                return true
            }
            guard plan.owner.id == me.id else { return false }
            let primary = await app.isPrimaryPhone()
            _ = await apply(plan, primary: primary, api: app.api)
            return true
        default:
            return false
        }
    }

    private func reconcileAll(_ mine: [PlanDTO], primary: Bool, api: WakeMeAPI) async -> Bool {
        // Alarms are installed only from server data, so a routine the server no longer
        // lists (possible only after a server restore) stops ringing here.
        let listed = Set(mine.map(\.id))
        for id in WakeAlarms.shared.installedPlanIds() where !listed.contains(id) { WakeAlarms.shared.cancel(planId: id) }
        var reported = false
        for plan in mine {
            if await apply(plan, primary: primary, api: api) { reported = true }
        }
        return reported
    }

    /// Arms, keeps or removes one of my routines on this phone and reports what AlarmKit
    /// accepted. Returns true when a receipt reached the server.
    private func apply(_ plan: PlanDTO, primary: Bool, api: WakeMeAPI) async -> Bool {
        let started = epoch
        guard let v = plan.accepted, plan.state == "active", primary else {
            WakeAlarms.shared.cancel(planId: plan.id)
            return false
        }
        // The challenge is installed and verified now, so the wake-up never needs the network.
        // If it can't be, the alarm still rings, without the challenge, and the server is told.
        var challenge = v.challengeRevisionId
        if let revision = challenge, !(await ChallengeStore.shared.ensureInstalled(revision, api: api)) { challenge = nil }
        let errorCode = v.challengeRevisionId != nil && challenge == nil ? "challenge_unavailable" : nil
        // Without a challenge there's nothing on iPhone that can cancel a follow-up, so none is armed.
        let routine = InstalledRoutine(planId: plan.id, version: v.version, label: v.label, localTime: v.localTime, timeZone: v.timeZone, timeZonePolicy: v.timeZonePolicy,
                                       recurrence: v.recurrence, soundId: v.soundId, ringSeconds: v.ringSeconds, challengeRevisionId: challenge,
                                       followUpDelaySeconds: challenge == nil ? nil : v.followUpDelaySeconds, paused: false)
        let ready = plan.readiness == "ready" || plan.readiness == "ready_edit_pending"
        let reportedNext = plan.installation?.nextFireAt.flatMap { try? Instant.parse($0) } // server sends milliseconds

        guard epoch == started else { return false }
        if let installed = WakeAlarms.shared.armedRoutine(planId: plan.id), installed.routine == routine, installed.errorCode == errorCode {
            // Already armed as is: only add what entered the window. Installing again would drop
            // a follow-up still pending for an occurrence that just rang.
            let added = await WakeAlarms.shared.topUp(planId: plan.id)
            guard signedOutMeanwhile(plan.id, since: started) == false else { return false }
            let next = WakeAlarms.shared.armed(planId: plan.id).first { $0.role == .main }?.fireAt
            guard next != nil, added || !ready || reportedNext != next else { return false }
            return await report(plan.id, version: v.version, errorCode: errorCode, api: api)
        }

        let receipt: WakeMeAPI.InstallationReceipt
        let result = await WakeAlarms.shared.install(routine, label: v.label, errorCode: errorCode)
        guard signedOutMeanwhile(plan.id, since: started) == false else { return false }
        switch result {
        case .scheduled(let dates):
            // Same version already confirmed with the same next ring: nothing new to report.
            if ready, plan.installation?.version == v.version, reportedNext == dates.first, errorCode == plan.installation?.errorCode { return false }
            receipt = .init(version: v.version, result: "scheduled", nextFireAt: dates.first.map(Instant.format), scheduledInstants: dates.map(Instant.format), errorCode: errorCode)
        case .nothingToSchedule:
            return false
        case .notAuthorized:
            receipt = .init(version: v.version, result: "failed", errorCode: "alarm_permission_missing")
        case .failed(let code):
            receipt = .init(version: v.version, result: "failed", errorCode: code)
        }
        return await send(plan.id, receipt, api: api)
    }

    private func topUpAll(api: WakeMeAPI) async {
        let started = epoch
        for planId in WakeAlarms.shared.installedPlanIds() {
            guard await WakeAlarms.shared.topUp(planId: planId), !signedOutMeanwhile(planId, since: started),
                  let armed = WakeAlarms.shared.armedRoutine(planId: planId) else { continue }
            _ = await report(planId, version: armed.routine.version, errorCode: armed.errorCode, api: api)
        }
    }

    /// Signed out while AlarmKit was busy: remove what this routine has armed.
    private func signedOutMeanwhile(_ planId: String, since started: Int) -> Bool {
        guard epoch != started else { return false }
        WakeAlarms.shared.cancel(planId: planId)
        return true
    }

    /// Installation receipt with everything armed for the routine from now on (ADR 0011).
    private func report(_ planId: String, version: Int, errorCode: String?, api: WakeMeAPI) async -> Bool {
        let armed = WakeAlarms.shared.armed(planId: planId)
        let next = armed.first { $0.role == .main }?.fireAt
        return await send(planId, .init(version: version, result: "scheduled", nextFireAt: next.map(Instant.format), scheduledInstants: armed.map { Instant.format($0.fireAt) }, errorCode: errorCode), api: api)
    }

    private func send(_ planId: String, _ receipt: WakeMeAPI.InstallationReceipt, api: WakeMeAPI) async -> Bool {
        do {
            try await api.reportInstallation(planId: planId, receipt)
            return true
        } catch let e as APIError where (400..<500).contains(e.status) && e.status != 401 && e.status != 429 {
            return false // no longer applicable (routine ended, account changed)
        } catch {
            defaults.set(true, forKey: reconcileKey) // try again from the full list on the next sync
            return false
        }
    }
}
