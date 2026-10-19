import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Literal readiness copy. "Ready" means this phone's OS accepted the alarm, not just that the server saved it.
func readinessText(_ r: String, owner: String, mine: Bool) -> (String, Tone) {
    let place = mine ? "this phone" : "\(owner)'s phone"
    switch r {
    case "ready": return ("Ready on \(place)", .success)
    case "ready_edit_pending": return ("Ready on \(place) · a change is waiting for approval", .info)
    case "awaiting_acceptance": return (mine ? "Waiting for you to accept" : "Waiting for \(owner) to accept", .info)
    case "awaiting_installation": return ("Saved · not yet confirmed on \(place)", .warning)
    case "install_failed": return ("Couldn't schedule on \(place). Check alarm permission.", .danger)
    case "paused": return ("Paused", .neutral)
    case "permission_lost": return ("Permission removed", .warning)
    case "finished": return ("Finished", .neutral)
    case "declined": return ("Declined", .neutral)
    default: return ("Ended", .neutral)
    }
}

func daysText(_ r: Recurrence) -> String {
    if r.kind == "once" { return "Once on \(r.date ?? "")" }
    let d = (r.days ?? []).sorted()
    switch d {
    case [1, 2, 3, 4, 5]: return "Weekdays"
    case [6, 7]: return "Weekends"
    case [1, 2, 3, 4, 5, 6, 7]: return "Every day"
    default: return d.map { ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][$0 - 1] }.joined(separator: ", ")
    }
}

struct RoutinesView: View {
    @Environment(AppModel.self) private var app
    @State private var plans: PlansDTO?
    @State private var message: String?
    @State private var editing = false
    @State private var ending: PlanDTO?

    var body: some View {
        List {
            if WakeAlarms.shared.authorization != .authorized {
                Section {
                    StatusPill(text: "Alarms aren't allowed on this phone yet", tone: .danger)
                    NavigationLink("Make this phone ready") { ReadyView() }
                }
            }
            let mine = plans?.mine ?? []
            let proposals = mine.filter { $0.pending != nil }
            if !proposals.isEmpty {
                Section("Waiting for you") { ForEach(proposals) { p in PlanRow(plan: p, mine: true, onAccept: { await act { try await app.api.acceptPlan(p.id, version: p.pending!.version) } }, onDecline: { await act { try await app.api.declinePlan(p.id, version: p.pending!.version) } }) } }
            }
            Section("On this phone") {
                let active = mine.filter { $0.accepted != nil && !["cancelled", "declined"].contains($0.state) }
                if active.isEmpty { Text("No alarms yet. Set one for yourself, or let a friend suggest one.").foregroundStyle(WMColor.textSecondary) }
                ForEach(active) { p in
                    PlanRow(plan: p, mine: true, onAccept: nil, onDecline: nil)
                        .swipeActions {
                            Button("End", role: .destructive) { ending = p }
                            Button(p.state == "paused" ? "Resume" : "Pause") { Task { await act { try await app.api.pausePlan(p.id, paused: p.state != "paused") } } }
                        }
                        .contextMenu {
                            Button(p.state == "paused" ? "Resume" : "Pause") { Task { await act { try await app.api.pausePlan(p.id, paused: p.state != "paused") } } }
                            Button("End this routine", role: .destructive) { ending = p }
                        }
                }
                Button("Set a wake-up") { editing = true }
            }
            if let helping = plans?.helping, !helping.isEmpty {
                Section("Helping friends") { ForEach(helping) { p in PlanRow(plan: p, mine: false, onAccept: nil, onDecline: nil) } }
            }
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("Routines")
        .refreshable { await load() }
        .task { await load() }
        .sheet(isPresented: $editing) { RoutineEditorView(onSaved: { await load() }) }
        .confirmationDialog("End this routine?", isPresented: Binding(get: { ending != nil }, set: { if !$0 { ending = nil } }), presenting: ending) { p in
            Button("End routine", role: .destructive) { Task { await act { try await app.api.cancelPlan(p.id) } } }
        } message: { _ in
            Text("It stops ringing on this phone straight away. You can set a new one any time.")
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func act(_ block: () async throws -> PlanDTO) async {
        do {
            let plan = try await block()
            // Ending or pausing stops the local alarms at once, even before the list reloads.
            if plan.state != "active" { WakeAlarms.shared.cancel(planId: plan.id) }
        } catch {
            message = (error as? APIError)?.message ?? "That didn't go through."
        }
        await load()
    }

    private func load() async {
        guard let p = try? await app.api.plans() else { return }
        plans = p
        // A receipt changes the server's readiness line; show what this phone just reported.
        if await install(p.mine), let fresh = try? await app.api.plans() { plans = fresh }
    }

    /// Installs accepted versions with AlarmKit on the primary phone and reports exactly
    /// what the OS accepted; ended or paused routines are cancelled locally first.
    @discardableResult
    private func install(_ mine: [PlanDTO]) async -> Bool {
        var reported = false
        let primary = await app.isPrimaryPhone()
        // Alarms are installed only from server data, so a routine the server no longer
        // lists (possible only after a server restore) stops ringing here.
        let listed = Set(mine.map(\.id))
        for id in WakeAlarms.shared.installedPlanIds() where !listed.contains(id) { WakeAlarms.shared.cancel(planId: id) }
        for plan in mine {
            guard let v = plan.accepted, plan.state == "active", primary else {
                WakeAlarms.shared.cancel(planId: plan.id)
                continue
            }
            // The challenge is installed and verified now, so the wake-up never needs the network.
            // If it can't be, the alarm still rings, without the challenge, and the server is told.
            var challenge = v.challengeRevisionId
            if let revision = challenge {
                let installed = await ChallengeStore.shared.ensureInstalled(revision, api: app.api)
                if !installed { challenge = nil }
            }
            let challengeMissing = v.challengeRevisionId != nil && challenge == nil
            let routine = InstalledRoutine(planId: plan.id, version: v.version, label: v.label, localTime: v.localTime, timeZone: v.timeZone, timeZonePolicy: v.timeZonePolicy,
                                           recurrence: v.recurrence, soundId: v.soundId, ringSeconds: v.ringSeconds, challengeRevisionId: challenge,
                                           followUpDelaySeconds: challenge == nil ? nil : v.followUpDelaySeconds, paused: false)
            let receipt: WakeMeAPI.InstallationReceipt
            switch await WakeAlarms.shared.install(routine, label: v.label) {
            case .scheduled(let dates):
                // Same version already confirmed with the same next ring: nothing new to report.
                let reportedNext = plan.installation?.nextFireAt.flatMap { try? Instant.parse($0) } // server sends milliseconds
                if plan.readiness == "ready", plan.installation?.version == v.version, reportedNext == dates.first, !challengeMissing { continue }
                receipt = .init(version: v.version, result: "scheduled", nextFireAt: dates.first.map(Instant.format), scheduledInstants: dates.map(Instant.format), errorCode: challengeMissing ? "challenge_unavailable" : nil)
            case .nothingToSchedule:
                continue
            case .notAuthorized:
                receipt = .init(version: v.version, result: "failed", errorCode: "alarm_permission_missing")
            case .failed(let code):
                receipt = .init(version: v.version, result: "failed", errorCode: code)
            }
            if (try? await app.api.reportInstallation(planId: plan.id, receipt)) != nil { reported = true }
        }
        return reported
    }
}

private struct PlanRow: View {
    let plan: PlanDTO
    let mine: Bool
    let onAccept: (() async -> Void)?
    let onDecline: (() async -> Void)?

    var body: some View {
        let v = plan.pending ?? plan.accepted
        VStack(alignment: .leading, spacing: WMSpacing.xs) {
            if let v {
                Text("\(v.localTime) · \(daysText(v.recurrence))").font(.title3).foregroundStyle(WMColor.textPrimary)
                if !v.label.isEmpty { Text(v.label).foregroundStyle(WMColor.textSecondary) }
            }
            if !mine { Text("For \(plan.owner.displayName)").foregroundStyle(WMColor.textSecondary) }
            else if plan.creator.id != plan.owner.id { Text("Suggested by \(plan.creator.displayName)").foregroundStyle(WMColor.textSecondary) }
            let readiness = readinessText(plan.readiness, owner: plan.owner.displayName, mine: mine)
            StatusPill(text: readiness.0, tone: readiness.1)
            if let onAccept, let onDecline {
                Text("Nothing changes until you accept.").font(.footnote).foregroundStyle(WMColor.textSecondary)
                HStack {
                    Button("Accept") { Task { await onAccept() } }.buttonStyle(.borderedProminent).tint(WMColor.accent).foregroundStyle(WMColor.textOnAccent)
                    Button("Decline") { Task { await onDecline() } }.buttonStyle(.bordered)
                }
            }
        }
        .padding(.vertical, WMSpacing.xs)
    }
}
