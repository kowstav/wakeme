import AppIntents
import Foundation
import WakeMeAPI
import WakeMeCore

/// A friend who currently allows you to send quacks, for Shortcuts, Siri and Spotlight (doc 06:
/// App Intents reduce the sender's effort; the server still authorizes every request).
struct QuackFriend: AppEntity {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Friend"
    static let defaultQuery = QuackFriendQuery()

    let id: String
    let name: String
    let grantVersion: Int?
    let soundId: String

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct QuackFriendQuery: EntityQuery {
    /// Only friends whose permission currently allows a quack; nothing is guessed offline.
    private func friends() async throws -> [QuackFriend] {
        await Session.shared.restore()
        return try await WakeMeAPI.app.friends().compactMap { f in
            guard let quack = f.actions?.first(where: { $0.capability == Capability.nudge }), quack.granted else { return nil }
            return QuackFriend(id: f.user.id, name: f.user.displayName, grantVersion: f.theyAllowMe?.version,
                               soundId: f.theyAllowMe?.limits.nudge?.sounds.first ?? "bundled:quack.classic")
        }
    }

    func entities(for identifiers: [QuackFriend.ID]) async throws -> [QuackFriend] {
        try await friends().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [QuackFriend] { try await friends() }
}

struct SendQuackIntent: AppIntent {
    static let title: LocalizedStringResource = "Send a quack"
    static let description = IntentDescription("Sends a quack to a friend who allows it, within the hours and limits they chose.")

    @Parameter(title: "Friend") var friend: QuackFriend

    func perform() async throws -> some IntentResult & ProvidesDialog {
        await Session.shared.restore()
        do {
            let sent = try await WakeMeAPI.app.send(.init(targetUserId: friend.id, capability: Capability.nudge, expectedGrantVersion: friend.grantVersion, soundId: friend.soundId))
            // Literal: sent is not "heard" (doc 03 §7).
            return .result(dialog: "Quack sent to \(friend.name). \(statusText(sent.displayStatus)).")
        } catch let e as APIError {
            return .result(dialog: "\(e.message)")
        } catch {
            return .result(dialog: "You're offline. Nothing was sent.")
        }
    }
}

struct WakeMeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SendQuackIntent(), phrases: ["Send a quack with \(.applicationName)", "Quack a friend with \(.applicationName)"],
                    shortTitle: "Send a quack", systemImageName: "bell.badge")
    }
}
