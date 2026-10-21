import SwiftUI
import WakeMeAPI
import WakeMeCore

/// Messages with an accepted friend (ADR 0003: messaging is not a grant; blocking ends it).
/// Long-press a message to react, delete your own, or report theirs. Mirrors the Android
/// ConversationScreen; refreshes every few seconds while open (push isn't set up on iOS yet).
struct ConversationView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let conversationId: String
    let friendName: String
    @State private var messages: [MessageDTO] = []
    @State private var loaded = false
    @State private var draft = ""
    @State private var sending = false
    @State private var ended = false
    @State private var reporting: MessageDTO?
    @State private var notice: String?

    private static let reactions = ["👍", "❤️", "😂", "🦆", "🙌", "😴", "☀️"]
    private static let reportReasons = [("harassment", "Harassment"), ("spam", "Spam"), ("inappropriate_content", "Inappropriate"), ("other", "Something else")]

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: WMSpacing.xs) {
                        if loaded && messages.isEmpty {
                            Text("No messages yet. Say hi, or send a quack from their profile.").foregroundStyle(WMColor.textSecondary)
                        }
                        ForEach(messages) { m in
                            bubble(m).id(m.id)
                                .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                        }
                    }
                    .padding(WMSpacing.md)
                    // New messages settle in from below; the list never jumps (Reduce Motion: fade).
                    .animation(WMMotion.respecting(reduceMotion, WMMotion.smooth), value: messages.map(\.id))
                }
                .onChange(of: messages.last?.id) { _, last in
                    if let last { withAnimation(WMMotion.respecting(reduceMotion, WMMotion.smooth)) { proxy.scrollTo(last, anchor: .bottom) } }
                }
            }
            if ended {
                Text("This conversation is no longer available.")
                    .foregroundStyle(WMColor.textSecondary).padding(WMSpacing.md).frame(maxWidth: .infinity, alignment: .leading)
            } else {
                composer
            }
        }
        .background(WMColor.surface)
        .navigationTitle(friendName)
        .appSwitcherCover()
        .navigationBarTitleDisplayMode(.inline)
        .task { await poll() }
        .confirmationDialog("Report this message?", isPresented: Binding(get: { reporting != nil }, set: { if !$0 { reporting = nil } }), titleVisibility: .visible, presenting: reporting) { m in
            ForEach(Self.reportReasons, id: \.0) { reason in
                Button(reason.1) { Task { await report(m, reason.0) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("The sender isn't told who reported it.")
        }
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: WMSpacing.xs) {
            TextField("Message", text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("messageField")
                .onChange(of: draft) { _, v in if v.count > 2000 { draft = String(v.prefix(2000)) } }
            Button(sending ? "Sending…" : "Send") { Task { await send() } }
                .font(.headline)
                .foregroundStyle(WMColor.textOnAccent)
                .padding(.horizontal, WMSpacing.md)
                .frame(minHeight: WMSize.minTouchTarget)
                .buttonStyle(WMPressStyle(fill: WMColor.accent, pressedFill: WMColor.accentPressed))
                .disabled(sending || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(WMSpacing.sm)
        .background(WMColor.surfaceRaised)
    }

    @ViewBuilder private func bubble(_ m: MessageDTO) -> some View {
        let mine = m.senderId == app.me?.id
        let myReaction = m.reactions.first { $0.userId == app.me?.id }?.emoji
        // Your messages are butter with a small corner toward you; theirs sit on the raised surface.
        let shape = UnevenRoundedRectangle(topLeadingRadius: 18, bottomLeadingRadius: mine ? 18 : 6, bottomTrailingRadius: mine ? 6 : 18, topTrailingRadius: 18)
        VStack(alignment: mine ? .trailing : .leading, spacing: WMSpacing.xxs) {
            Text(m.body ?? "Message deleted")
                .italic(m.body == nil)
                .foregroundStyle(mine ? WMColor.onAccentContainer : WMColor.textPrimary)
                .padding(.horizontal, WMSpacing.md).padding(.vertical, WMSpacing.sm)
                .background(mine ? WMColor.accentContainer : WMColor.surfaceRaised, in: shape)
                .overlay(shape.stroke(mine ? WMColor.accentContainer : WMColor.border))
            if !m.reactions.isEmpty {
                Text(m.reactions.map(\.emoji).joined()).font(.footnote)
                    .accessibilityLabel("Reactions: " + m.reactions.map(\.emoji).joined(separator: " "))
            }
        }
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
        .contextMenu {
            if m.body != nil {
                ForEach(Self.reactions, id: \.self) { e in
                    Button(e == myReaction ? "\(e) (yours)" : e) { Task { await react(m, e) } }
                }
                if myReaction != nil { Button("Remove my reaction") { Task { await removeReaction(m) } } }
                if mine {
                    Button("Delete for everyone", role: .destructive) { Task { await delete(m) } }
                } else {
                    Button("Report message", role: .destructive) { reporting = m }
                }
            }
        }
    }

    private func poll() async {
        var lastRead: String?
        while !Task.isCancelled && !ended {
            await load()
            if let newest = messages.last?.id, newest != lastRead {
                lastRead = newest
                try? await app.api.markRead(conversationId)
            }
            try? await Task.sleep(for: .seconds(5))
        }
    }

    private func load() async {
        do {
            // The server sends newest first.
            messages = try await app.api.messages(conversationId).reversed()
            loaded = true
        } catch let e as APIError where e.status == 404 || e.status == 403 {
            ended = true // a blocked or removed friendship ends the conversation
        } catch {
            loaded = true // offline: keep what is shown
        }
    }

    private func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        sending = true
        defer { sending = false }
        do {
            let sent = try await app.api.sendMessage(conversationId, text: text)
            draft = ""
            if !messages.contains(where: { $0.id == sent.id }) { messages.append(sent) }
        } catch let e as APIError where e.status == 404 || e.status == 403 {
            ended = true
        } catch let e as APIError {
            notice = e.message
        } catch {
            notice = "You're offline. The message wasn't sent; it's still in the box."
        }
    }

    private func react(_ m: MessageDTO, _ emoji: String) async {
        await act { try await app.api.react(m.id, emoji: emoji) }
    }

    private func removeReaction(_ m: MessageDTO) async {
        await act { try await app.api.removeReaction(m.id) }
    }

    private func delete(_ m: MessageDTO) async {
        await act { try await app.api.deleteMessage(m.id) }
    }

    private func report(_ m: MessageDTO, _ reason: String) async {
        do {
            try await app.api.report(targetKind: "message", targetId: m.id, reason: reason)
            notice = "Thanks. We'll look at it. \(friendName) isn't told who reported it."
        } catch {
            notice = (error as? APIError)?.message ?? "You're offline. The report wasn't sent."
        }
    }

    private func act(_ block: () async throws -> Void) async {
        do {
            try await block()
            await load()
        } catch {
            notice = (error as? APIError)?.message ?? "You're offline. Nothing was changed."
        }
    }
}
