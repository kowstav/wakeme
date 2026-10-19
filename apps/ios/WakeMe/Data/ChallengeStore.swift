import Foundation
import WakeMeAPI
import WakeMeCore

/// Published challenge revisions kept on the phone for offline wake-ups (ADR 0008). Each is
/// downloaded once, its ES256 signature verified against the server's published keys, and
/// stored as JSON in Application Support. Same rules as Android's ChallengeInstaller: the key
/// list is cached for a day and refreshed once when a revision names an unknown key.
actor ChallengeStore {
    static let shared = ChallengeStore()

    private let defaults = UserDefaults.standard
    private let keysKey = "challenge.keys"
    private let keysAtKey = "challenge.keys.at"
    private let maxKeyAge: TimeInterval = 24 * 3600

    private var directory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("challenges", isDirectory: true)
    }

    private func file(_ revisionId: String) -> URL {
        // Revision IDs are UUIDs from the server; keep only safe characters for the file name.
        directory.appendingPathComponent(revisionId.filter { $0.isLetter || $0.isNumber || $0 == "-" } + ".json")
    }

    /// The installed definition, or nil if this phone doesn't have it.
    func definition(_ revisionId: String) -> ChallengeDefinition? {
        guard let data = try? Data(contentsOf: file(revisionId)) else { return nil }
        return try? JSONDecoder().decode(ChallengeDefinition.self, from: data)
    }

    /// Downloads and verifies the revision if it isn't installed yet. False when it can't be
    /// installed now (offline, unknown key, bad signature); the routine then rings without it.
    func ensureInstalled(_ revisionId: String, api: WakeMeAPI) async -> Bool {
        if definition(revisionId) != nil { return true }
        do {
            let envelope = try await api.challengeRevision(revisionId)
            var keys = try await trustedKeys(api: api, refresh: false)
            if keys[envelope.keyId] == nil { keys = try await trustedKeys(api: api, refresh: true) }
            let payload = try ChallengeEnvelopes.verify(envelope, trustedKeys: keys)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(payload.definition).write(to: file(revisionId), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            return false
        }
    }

    /// Removes every installed revision (sign-out).
    func clear() {
        try? FileManager.default.removeItem(at: directory)
        defaults.removeObject(forKey: keysKey)
        defaults.removeObject(forKey: keysAtKey)
    }

    private func trustedKeys(api: WakeMeAPI, refresh: Bool) async throws -> [String: String] {
        let cached = defaults.dictionary(forKey: keysKey) as? [String: String] ?? [:]
        let fresh = Date().timeIntervalSince1970 - defaults.double(forKey: keysAtKey) < maxKeyAge
        if !cached.isEmpty, fresh, !refresh { return cached }
        do {
            let keys = Dictionary(try await api.config().challengeSigningKeys.map { ($0.keyId, $0.spki) }, uniquingKeysWith: { first, _ in first })
            defaults.set(keys, forKey: keysKey)
            defaults.set(Date().timeIntervalSince1970, forKey: keysAtKey)
            return keys
        } catch {
            if !cached.isEmpty, !refresh { return cached }
            throw error
        }
    }
}
