import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import XCTest
import WakeMeAPI
import WakeMeCore

/// Contract checks between this client and the real server code: run against the local dev
/// backend (services/api `npm run dev`) when WAKEME_DEV_BACKEND is set, e.g.
/// `WAKEME_DEV_BACKEND=http://127.0.0.1:8787 swift test`. Skipped otherwise.
final class DevBackendTests: XCTestCase {
    final class Token: TokenProvider, @unchecked Sendable {
        var value: String?
        func accessToken() async -> String? { value }
        func refresh() async -> Bool { false }
    }

    final class Signer: DeviceSigner, @unchecked Sendable {
        let key = P256.Signing.PrivateKey()
        var id: String?
        func deviceId() async -> String? { id }
        func signBase64(_ data: Data) async throws -> String { try key.signature(for: data).derRepresentation.base64EncodedString() }
    }

    private func devToken(_ base: URL) async throws -> String {
        var r = URLRequest(url: base.appendingPathComponent("dev/token"))
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "content-type")
        r.httpBody = Data("{}".utf8)
        let (data, _) = try await URLSession.shared.data(for: r)
        return try XCTUnwrap((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["accessToken"] as? String)
    }

    func testOwnRoutineIsReadyAfterTheSignedInstallationReceipt() async throws {
        guard let raw = ProcessInfo.processInfo.environment["WAKEME_DEV_BACKEND"], let base = URL(string: raw) else {
            throw XCTSkip("set WAKEME_DEV_BACKEND to run against the dev backend")
        }
        let token = Token()
        let signer = Signer()
        let api = WakeMeAPI(client: APIClient(baseURL: base, tokens: token, signer: signer))
        token.value = try await devToken(base)
        _ = try await api.createAccount(name: "Sam", timeZone: "UTC", locale: "en-US")
        let device = try await api.registerDevice(
            installId: "i_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased(),
            osVersion: "26.0", appBuild: "1",
            publicKeySPKIBase64: signer.key.publicKey.derRepresentation.base64EncodedString(),
            push: nil,
            capabilities: .init(notificationsAuthorized: false, alarmAuthorized: true),
            makePrimary: true
        )
        signer.id = device.id

        let plan = try await api.createPlan(.init(label: "", localTime: "07:00", timeZone: "UTC", timeZonePolicy: "fixed", recurrence: .weekly([1, 2, 3, 4, 5]), soundId: "bundled:rise.gentle", ringSeconds: 90))
        XCTAssertEqual(plan.state, "active")
        let version = try XCTUnwrap(plan.accepted?.version)
        let next = Date().addingTimeInterval(3600)
        try await api.reportInstallation(planId: plan.id, .init(version: version, result: "scheduled", nextFireAt: Instant.format(next), scheduledInstants: [Instant.format(next)]))

        let mine = try await api.plans().mine
        XCTAssertEqual(mine.first { $0.id == plan.id }?.readiness, "ready", "the server counts this phone's receipt")
    }
}
