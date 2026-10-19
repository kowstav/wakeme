import Foundation
import WakeMeAPI

enum AuthError: LocalizedError {
    case notConfigured, rejected(String)
    var errorDescription: String? {
        switch self {
        case .notConfigured: "Sign-in isn't configured for this build."
        case .rejected(let m): m
        }
    }
}

/// Tokens for the signed-in identity. Access tokens stay in memory; the refresh token
/// is kept in the Keychain. Mirrors the Android SessionManager (Supabase GoTrue, or the
/// local dev token issuer in debug builds).
actor Session: TokenProvider {
    static let shared = Session()
    private var access: String?
    private var accessExpiresAt = Date.distantPast
    private(set) var subject: String?

    var isSignedIn: Bool { subject != nil }

    func restore() async {
        subject = Keychain.string(for: "auth.subject")
        if subject != nil, Keychain.string(for: "auth.refresh") != nil { _ = await refresh() }
    }

    func accessToken() async -> String? {
        if access != nil, accessExpiresAt.timeIntervalSinceNow > 60 { return access }
        if await refresh() { return access }
        return access
    }

    func refresh() async -> Bool {
        guard AppConfig.supabaseConfigured, let refresh = Keychain.string(for: "auth.refresh") else { return false }
        do {
            try await save(gotrue("/auth/v1/token?grant_type=refresh_token", ["refresh_token": refresh]))
            return true
        } catch {
            return false
        }
    }

    func requestEmailCode(_ email: String) async throws {
        _ = try await gotrueRaw("/auth/v1/otp", ["email": email.trimmingCharacters(in: .whitespaces), "create_user": true])
    }

    func verifyEmailCode(_ email: String, code: String) async throws {
        try await save(gotrue("/auth/v1/verify", ["type": "email", "email": email.trimmingCharacters(in: .whitespaces), "token": code.trimmingCharacters(in: .whitespaces)]))
    }

    /// Debug builds without Supabase: a local test identity from the dev backend.
    func devSignIn() async throws {
        struct DevToken: Decodable { let subject: String; let accessToken: String; let expiresIn: Double }
        var req = URLRequest(url: AppConfig.apiBaseURL.appendingPathComponent("dev/token"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        req.httpBody = try JSONSerialization.data(withJSONObject: subject.map { ["subject": $0] } ?? [:])
        let (data, response) = try await URLSession.shared.data(for: req)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw AuthError.rejected("The local test backend isn't running.") }
        let t = try JSONDecoder().decode(DevToken.self, from: data)
        access = t.accessToken
        accessExpiresAt = Date().addingTimeInterval(t.expiresIn)
        subject = t.subject
        Keychain.set(t.subject, for: "auth.subject")
    }

    func signOut() async {
        if AppConfig.supabaseConfigured, let token = access {
            var req = URLRequest(url: URL(string: AppConfig.supabaseURL + "/auth/v1/logout")!)
            req.httpMethod = "POST"
            req.setValue(AppConfig.supabaseAnonKey, forHTTPHeaderField: "apikey")
            req.setValue("Bearer \(token)", forHTTPHeaderField: "authorization")
            _ = try? await URLSession.shared.data(for: req)
        }
        access = nil
        accessExpiresAt = .distantPast
        subject = nil
        Keychain.delete("auth.refresh")
        Keychain.delete("auth.subject")
    }

    private struct TokenResponse: Decodable {
        struct User: Decodable { let id: String }
        let access_token: String
        let refresh_token: String
        let expires_in: Double
        let user: User?
    }

    private func save(_ t: TokenResponse) {
        access = t.access_token
        accessExpiresAt = Date().addingTimeInterval(t.expires_in)
        Keychain.set(t.refresh_token, for: "auth.refresh")
        if let id = t.user?.id {
            subject = id
            Keychain.set(id, for: "auth.subject")
        }
    }

    private func gotrue(_ path: String, _ body: [String: Any]) async throws -> TokenResponse {
        try JSONDecoder().decode(TokenResponse.self, from: await gotrueRaw(path, body))
    }

    private func gotrueRaw(_ path: String, _ body: [String: Any]) async throws -> Data {
        guard AppConfig.supabaseConfigured, let url = URL(string: AppConfig.supabaseURL + path) else { throw AuthError.notConfigured }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        req.setValue(AppConfig.supabaseAnonKey, forHTTPHeaderField: "apikey")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["msg"] as? String
            throw AuthError.rejected(message ?? "That didn't work. Check the code and try again.")
        }
        return data
    }
}
