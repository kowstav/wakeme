import SwiftUI
import WakeMeAPI

/// Private circles (doc 04 Stage 4), mirroring Android's Circles.kt: a group with its own chat.
/// Membership never grants anything on anyone's phone; each person still chooses, friend by friend.
struct CirclesSection: View {
    @Environment(AppModel.self) private var app
    @State private var circles: [CircleDTO] = []
    @State private var naming = false
    @State private var name = ""
    @State private var message: String?

    var body: some View {
        Section {
            if circles.isEmpty {
                Text("A circle is a private group: your flat, study group or team, with its own chat.")
                    .foregroundStyle(WMColor.textSecondary)
            }
            ForEach(circles) { c in
                NavigationLink {
                    CircleView(circleId: c.id)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(c.name).font(.headline).foregroundStyle(WMColor.textPrimary)
                        Text("\(c.memberCount) member\(c.memberCount == 1 ? "" : "s")").font(.subheadline).foregroundStyle(WMColor.textSecondary)
                    }
                    .frame(minHeight: WMSize.minTouchTarget)
                }
            }
            Button("New circle") { naming = true }.frame(minHeight: WMSize.minTouchTarget)
        } header: {
            Text("Circles")
        }
        .task { await load() }
        .alert("New circle", isPresented: $naming) {
            TextField("Circle name", text: $name)
            Button("Create") { Task { await create() } }
            Button("Cancel", role: .cancel) { name = "" }
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func load() async {
        if let list = try? await app.api.circles() { circles = list }
    }

    private func create() async {
        let trimmed = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        name = ""
        guard !trimmed.isEmpty else { return }
        do {
            _ = try await app.api.createCircle(name: trimmed)
            await load()
        } catch {
            message = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }
}

struct CircleView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let circleId: String
    @State private var detail: CircleDetailDTO?
    @State private var invite: InviteCreatedDTO?
    @State private var confirmLeave = false
    @State private var renaming = false
    @State private var newName = ""
    @State private var message: String?

    var body: some View {
        List {
            if let d = detail {
                let admin = d.circle.role == "owner" || d.circle.role == "admin"
                Section {
                    Text("Being in a circle together doesn't let anyone do anything on your phone. Each person still chooses that, friend by friend.")
                        .foregroundStyle(WMColor.textSecondary)
                    NavigationLink {
                        ConversationView(conversationId: d.circle.conversationId, friendName: d.circle.name)
                    } label: {
                        Label("Messages", systemImage: "bubble.left.and.bubble.right").frame(minHeight: WMSize.minTouchTarget, alignment: .leading)
                    }
                    Button("Invite to this circle") { Task { await createInvite() } }.frame(minHeight: WMSize.minTouchTarget)
                }
                Section("Members") {
                    ForEach(d.members, id: \.user.id) { m in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(m.user.displayName + (m.user.id == app.me?.id ? " (you)" : "")).foregroundStyle(WMColor.textPrimary)
                                Text(roleText(m)).font(.subheadline).foregroundStyle(WMColor.textSecondary)
                            }
                            Spacer()
                            if admin && m.role != "owner" && m.user.id != app.me?.id {
                                Button("Remove") { Task { await remove(m.user.id) } }
                                    .buttonStyle(.borderless)
                                    .accessibilityLabel("Remove \(m.user.displayName)")
                            }
                        }
                        .frame(minHeight: WMSize.minTouchTarget)
                    }
                }
                Section {
                    if admin { Button("Rename circle") { newName = d.circle.name; renaming = true } }
                    Button("Leave circle", role: .destructive) { confirmLeave = true }
                }
            } else {
                ProgressView()
            }
        }
        .scrollContentBackground(.hidden)
        .background(WMColor.surface)
        .navigationTitle(detail?.circle.name ?? "Circle")
        .task { await load() }
        .sheet(item: $invite) { inv in InviteSheet(invite: inv) }
        .alert("Leave \(detail?.circle.name ?? "this circle")?", isPresented: $confirmLeave) {
            Button("Leave", role: .destructive) { Task { await leave() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll stop seeing its messages. Friendships and the permissions you chose stay as they are.")
        }
        .alert("Rename circle", isPresented: $renaming) {
            TextField("Circle name", text: $newName)
            Button("Save") { Task { await rename() } }
            Button("Cancel", role: .cancel) {}
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") { message = nil } }
    }

    private func roleText(_ m: CircleMemberDTO) -> String {
        switch m.role {
        case "owner": return "Owner"
        case "admin": return "Admin"
        default: return m.user.id == app.me?.id ? "Member" : m.isFriend ? "Friend" : "Not your friend: no permissions either way"
        }
    }

    private func load() async { await run { detail = try await app.api.circle(circleId) } }
    private func createInvite() async { await run { invite = try await app.api.createCircleInvite(circleId) } }
    private func remove(_ userId: String) async { await run { try await app.api.removeCircleMember(circleId, userId: userId); detail = try await app.api.circle(circleId) } }
    private func rename() async {
        let trimmed = String(newName.trimmingCharacters(in: .whitespaces).prefix(40))
        guard !trimmed.isEmpty else { return }
        await run { _ = try await app.api.renameCircle(circleId, name: trimmed); detail = try await app.api.circle(circleId) }
    }
    private func leave() async { await run { try await app.api.leaveCircle(circleId); dismiss() } }

    private func run(_ work: () async throws -> Void) async {
        do { try await work() } catch { message = (error as? APIError)?.message ?? "You're offline. Nothing was changed." }
    }
}
