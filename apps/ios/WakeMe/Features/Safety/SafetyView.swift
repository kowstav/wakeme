import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Safety: owner-started, time-boxed check-ins and location shares with named friends, plus
/// SOS (same flows and wording as Android's SafetyScreen). Everything is visible to the sharer,
/// expires on its own and stops with one tap. Wake Me is not an emergency service.
struct SafetyView: View {
    enum Kind: String, Identifiable {
        case checkin, share, sos
        var id: String { rawValue }
    }

    @Environment(AppModel.self) private var app
    @State private var mine: [SafetySessionDTO] = []
    @State private var sharedWithMe: [SafetySessionDTO] = []
    @State private var friends: [FriendDTO] = []
    @State private var locations: [String: LatestLocationDTO] = [:]
    @State private var loaded = false
    @State private var offline = false
    @State private var busy = false
    @State private var composing: Kind?
    @State private var message: String?
    private var sharer: LocationSharer { .shared }

    var body: some View {
        List {
            Section {
                Text("Share with friends you choose, for a time you choose. Wake Me is not an emergency service: in an emergency, call your local emergency number.")
                    .foregroundStyle(WMColor.textSecondary)
                if offline { StatusPill(text: "You're offline. Showing what was last loaded.", tone: .warning) }
            }
            if let a = sharer.active {
                Section {
                    StatusPill(text: "Sharing your location now", tone: .info)
                    Text("With \(a.viewerNames.joined(separator: ", ")) until \(a.endsAt.formatted(date: .omitted, time: .shortened)).")
                        .foregroundStyle(WMColor.textPrimary)
                    Text(a.lastAccuracyMeters.map { "Last sent \(a.lastSentAt?.formatted(date: .omitted, time: .shortened) ?? "") · accuracy about \($0) m" }
                         ?? "Waiting for a location fix. Your friends see “No location available” until one arrives.")
                        .foregroundStyle(WMColor.textSecondary)
                }
            }
            let active = mine.filter(\.active)
            Section("You're sharing") {
                if active.isEmpty && sharer.active == nil { StatusPill(text: "You're not sharing anything") }
                ForEach(active) { s in mySession(s) }
            }
            Section("Start") {
                PrimaryButton(title: "Check in with friends later") { composing = .checkin }
                    .disabled(busy || friends.isEmpty)
                SecondaryButton(title: "Share my location for a while") { composing = .share }
                    .disabled(busy || friends.isEmpty)
                if loaded && friends.isEmpty {
                    Text("Add a friend first. Only friends you pick can see a session.").foregroundStyle(WMColor.textSecondary)
                }
            }
            let shared = sharedWithMe.filter(\.active)
            if !shared.isEmpty {
                Section("Shared with you") { ForEach(shared) { s in viewerSession(s) } }
            }
            Section {
                Button(role: .destructive) { composing = .sos } label: {
                    Text("Send SOS to friends").font(.headline).frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
                }
                .buttonStyle(.borderedProminent)
                .tint(WMColor.dangerFill)
                .foregroundStyle(WMColor.textOnDanger)
                .disabled(busy || friends.isEmpty)
                Text("Alerts the friends you pick, and shares your location for 2 hours if you allow it.").foregroundStyle(WMColor.textSecondary)
            }
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle("Safety")
        .task { await refreshLoop() }
        .refreshable { await load() }
        .sheet(item: $composing) { kind in
            StartSafetySheet(kind: kind, friends: friends) { viewers, minutes, shareLocation, checkinMinutes, note in
                composing = nil
                Task { await start(kind, viewers: viewers, minutes: minutes, shareLocation: shareLocation, checkinMinutes: checkinMinutes, note: note) }
            }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func title(_ purpose: String) -> String {
        switch purpose {
        case "sos": "SOS"
        case "checkin": "Check-in"
        default: "Location share"
        }
    }

    private func time(_ iso: String?) -> String {
        guard let iso, let d = try? Instant.parse(iso) else { return "—" }
        return d.formatted(date: .omitted, time: .shortened)
    }

    @ViewBuilder private func mySession(_ s: SafetySessionDTO) -> some View {
        VStack(alignment: .leading, spacing: WMSpacing.sm) {
            Text(title(s.purpose)).font(.title3.bold()).foregroundStyle(WMColor.textPrimary)
            let viewers = s.viewers.filter { !$0.revoked }.map(\.displayName)
            Text("Visible to \(viewers.isEmpty ? "nobody (all viewers removed)" : viewers.joined(separator: ", ")) until \(time(s.endsAt)).")
                .foregroundStyle(WMColor.textPrimary)
            let removed = s.viewers.filter(\.revoked).map(\.displayName)
            if !removed.isEmpty { Text("No longer visible to \(removed.joined(separator: ", ")).").foregroundStyle(WMColor.textSecondary) }
            StatusPill(text: s.shareLocation ? "Location: shared" : "Location: not shared", tone: s.shareLocation ? .info : .neutral)
            if s.shareLocation && sharer.active?.sessionId != s.id {
                Text("This phone isn't sending its location for this right now.").foregroundStyle(WMColor.textSecondary)
                SecondaryButton(title: "Send my location again") {
                    Task {
                        if await LocationPermission.shared.request() { sharer.start(s, viewerNames: viewers, api: app.api) }
                        else { message = "Location is off for Wake Me. Turn it on in Settings to share it." }
                    }
                }
            }
            if s.purpose == "sos" {
                let seen = s.viewers.filter { $0.acknowledgedAt != nil }.map(\.displayName)
                StatusPill(text: seen.isEmpty ? "Sent · nobody has confirmed seeing it yet" : "Seen by \(seen.joined(separator: ", "))", tone: seen.isEmpty ? .warning : .success)
            }
            if s.purpose == "checkin" {
                if s.checkedInAt != nil {
                    StatusPill(text: "Checked in at \(time(s.checkedInAt))", tone: .success)
                } else {
                    Text("If you don't check in by \(time(s.checkinDueAt)), they'll get a notice that you haven't checked in yet. That's all: nobody else is contacted.")
                        .foregroundStyle(WMColor.textSecondary)
                    PrimaryButton(title: "I'm OK — check in now") { Task { await act { _ = try await app.api.checkIn(s.id) } } }
                }
            }
            Button("Stop now", role: .destructive) {
                if sharer.active?.sessionId == s.id { sharer.stop() } // stop sending first, even offline
                Task { await act { _ = try await app.api.endSafety(s.id) } }
            }
            .buttonStyle(.borderless) // several buttons in one list row: each acts on its own tap only
            .frame(minHeight: WMSize.minTouchTarget)
        }
        .padding(.vertical, WMSpacing.xs)
    }

    @ViewBuilder private func viewerSession(_ s: SafetySessionDTO) -> some View {
        VStack(alignment: .leading, spacing: WMSpacing.sm) {
            let name = s.owner.displayName
            Text(s.purpose == "sos" ? "SOS from \(name)" : s.purpose == "checkin" ? "\(name)'s check-in" : "\(name) is sharing location")
                .font(.title3.bold()).foregroundStyle(WMColor.textPrimary)
            if let note = s.message { Text("“\(note)”").foregroundStyle(WMColor.textPrimary) }
            Text("Until \(time(s.endsAt))").foregroundStyle(WMColor.textSecondary)
            if s.purpose == "checkin" {
                StatusPill(text: s.checkedInAt != nil ? "Checked in at \(time(s.checkedInAt))" : "Check-in due at \(time(s.checkinDueAt))",
                           tone: s.checkedInAt != nil ? .success : .neutral)
            }
            let latest = locations[s.id]
            let label = LocationLabel(latest, shared: s.shareLocation)
            StatusPill(text: label.text, tone: tone(label.tone))
            if s.shareLocation, latest?.status == "available", let lat = latest?.latitude, let lon = latest?.longitude,
               let url = mapsURL(lat, lon, "\(name) at \(time(latest?.capturedAt))") {
                Link(destination: url) { Text("Open in Maps (position at \(time(latest?.capturedAt)))").frame(minHeight: WMSize.minTouchTarget) }
                    .buttonStyle(.borderless)
            }
            if s.purpose == "sos" {
                Text("Try calling \(name). If you think they're in danger, call your local emergency number.").foregroundStyle(WMColor.textPrimary)
                PrimaryButton(title: "I've seen this") { Task { await act { try await app.api.acknowledgeSafety(s.id) } } }
            }
        }
        .padding(.vertical, WMSpacing.xs)
    }

    private func tone(_ t: LocationLabel.Tone) -> Tone {
        switch t {
        case .neutral: .neutral
        case .info: .info
        case .warning: .warning
        }
    }

    private func mapsURL(_ lat: Double, _ lon: Double, _ label: String) -> URL? {
        var c = URLComponents(string: "https://maps.apple.com/")
        c?.queryItems = [URLQueryItem(name: "ll", value: "\(lat),\(lon)"), URLQueryItem(name: "q", value: label)]
        return c?.url
    }

    /// Viewers refresh while the screen is open; the server keeps only the latest sample.
    private func refreshLoop() async {
        await load()
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(30))
            if Task.isCancelled { break }
            await load()
        }
    }

    private func load() async {
        do {
            let sessions = try await app.api.safetySessions()
            mine = sessions.mine
            sharedWithMe = sessions.sharedWithMe
            if let f = try? await app.api.friends() { friends = f.filter { $0.state == "accepted" } }
            offline = false
            // Sending stops when a session ended elsewhere (another phone, or it expired).
            if let a = sharer.active, !mine.contains(where: { $0.id == a.sessionId && $0.active }) { sharer.stop() }
            for s in sharedWithMe where s.active && s.shareLocation {
                if let l = try? await app.api.latestLocation(s.id) { locations[s.id] = l }
            }
        } catch is OfflineError {
            offline = true
        } catch {
            message = (error as? APIError)?.message ?? "Couldn't load safety sessions."
        }
        loaded = true
    }

    private func act(_ block: () async throws -> Void) async {
        busy = true
        do {
            try await block()
        } catch is OfflineError {
            message = "You're offline. Nothing was sent. Call or text your friends directly if you need help."
        } catch {
            message = (error as? APIError)?.message ?? "Something went wrong. Nothing was sent."
        }
        busy = false
        await load()
    }

    private func start(_ kind: Kind, viewers: [FriendDTO], minutes: Int, shareLocation: Bool, checkinMinutes: Int?, note: String?) async {
        // Location only if the person allows it now; otherwise only the alert or check-in is sent.
        var share = shareLocation
        if share, !(await LocationPermission.shared.request()) {
            share = false
            message = "Location is off for Wake Me, so only the alert is sent, with no location."
        }
        let names = viewers.map(\.user.displayName)
        await act {
            let s: SafetySessionDTO
            switch kind {
            case .sos:
                s = try await app.api.sos(viewerIds: viewers.map(\.user.id), shareLocation: share)
            case .checkin, .share:
                s = try await app.api.startSafety(purpose: kind.rawValue, viewerIds: viewers.map(\.user.id), minutes: minutes, shareLocation: share,
                                                  checkinDueAt: checkinMinutes.map { Date().addingTimeInterval(TimeInterval($0 * 60)) }, message: note)
            }
            if s.shareLocation { sharer.start(s, viewerNames: names, api: app.api) }
        }
    }
}

/// Who, how long, whether to share location, and a note; the summary says exactly who is told
/// and until when before anything is sent.
private struct StartSafetySheet: View {
    let kind: SafetyView.Kind
    let friends: [FriendDTO]
    let onStart: (_ viewers: [FriendDTO], _ minutes: Int, _ shareLocation: Bool, _ checkinMinutes: Int?, _ note: String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<String> = []
    @State private var minutes = 60
    @State private var checkin = 60
    @State private var shareLocation = true
    @State private var note = ""

    private static let durations = [(30, "30 min"), (60, "1 hour"), (120, "2 hours"), (240, "4 hours"), (480, "8 hours")]
    private static let checkins = [15, 30, 60, 120, 240]

    init(kind: SafetyView.Kind, friends: [FriendDTO], onStart: @escaping ([FriendDTO], Int, Bool, Int?, String?) -> Void) {
        self.kind = kind
        self.friends = friends
        self.onStart = onStart
        _minutes = State(initialValue: kind == .checkin ? 120 : 60)
        _shareLocation = State(initialValue: kind != .checkin)
    }

    private var viewers: [FriendDTO] { friends.filter { selected.contains($0.friendshipId) } }

    var body: some View {
        NavigationStack {
            Form {
                Section("Who can see it?") {
                    ForEach(friends.prefix(10)) { f in
                        Toggle(f.user.displayName, isOn: Binding(
                            get: { selected.contains(f.friendshipId) },
                            set: { on in if on { selected.insert(f.friendshipId) } else { selected.remove(f.friendshipId) } }
                        ))
                    }
                }
                if kind != .sos {
                    Picker("For how long?", selection: $minutes) {
                        ForEach(Self.durations, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .onChange(of: minutes) { _, m in if checkin > m { checkin = Self.checkins.last { $0 <= m } ?? m } }
                }
                if kind == .checkin {
                    Picker("Check in within", selection: $checkin) {
                        ForEach(Self.checkins.filter { $0 <= minutes }, id: \.self) { m in Text(m < 60 ? "\(m) min" : "\(m / 60) h").tag(m) }
                    }
                }
                Section {
                    Toggle("Share my location", isOn: $shareLocation)
                } footer: {
                    Text("Friends see where you were, when it was captured and how accurate it is. iOS shows that Wake Me is using your location while it's on.")
                }
                if kind != .sos {
                    TextField("Note (optional)", text: $note).onChange(of: note) { _, v in if v.count > 200 { note = String(v.prefix(200)) } }
                }
                Section {
                    let until = Date().addingTimeInterval(TimeInterval((kind == .sos ? 120 : minutes) * 60)).formatted(date: .omitted, time: .shortened)
                    Text(viewers.isEmpty ? "Pick at least one friend."
                         : "\(viewers.map(\.user.displayName).joined(separator: ", ")) will be notified and can see this until \(until). You can stop it anytime.")
                        .foregroundStyle(WMColor.textPrimary)
                }
            }
            .navigationTitle(kind == .sos ? "Send SOS" : kind == .checkin ? "Check in later" : "Share my location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(kind == .sos ? "Send SOS" : "Start") {
                        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
                        onStart(viewers, minutes, shareLocation, kind == .checkin ? checkin : nil, trimmed.isEmpty ? nil : trimmed)
                    }
                    .disabled(viewers.isEmpty)
                }
            }
        }
    }
}
