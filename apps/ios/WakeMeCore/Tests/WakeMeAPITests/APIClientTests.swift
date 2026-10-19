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
@testable import WakeMeAPI
import WakeMeCore

/// Scripted transport: returns queued responses and records requests.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var queue: [(Int, String, [String: String])]
    private(set) var requests: [URLRequest] = []
    var failNext = 0

    init(_ responses: [(Int, String, [String: String])]) { queue = responses }

    func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try lock.withLock {
            requests.append(request)
            if failNext > 0 { failNext -= 1; throw URLError(.notConnectedToInternet) }
            let (status, body, headers) = queue.isEmpty ? (200, "{}", [:]) : queue.removeFirst()
            return (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: headers)!)
        }
    }
}

final class Tokens: TokenProvider, @unchecked Sendable {
    var token = "t1"
    var refreshes = 0
    func accessToken() async -> String? { token }
    func refresh() async -> Bool { refreshes += 1; token = "t2"; return true }
}

struct TestSigner: DeviceSigner {
    let key = P256.Signing.PrivateKey()
    func deviceId() async -> String? { "dev-1" }
    func signBase64(_ data: Data) async throws -> String { try key.signature(for: data).derRepresentation.base64EncodedString() }
}

final class APIClientTests: XCTestCase {
    let base = URL(string: "https://api.example.test")!
    func client(_ t: FakeTransport, _ tokens: Tokens = Tokens(), signer: DeviceSigner? = nil) -> APIClient {
        APIClient(baseURL: base, transport: t, tokens: tokens, signer: signer, sleep: { _ in })
    }

    func testIdempotentRequestsRetryServerErrors() async throws {
        let t = FakeTransport([(503, "{}", [:]), (200, #"{"ok":true}"#, [:])])
        let r: OkDTO = try await client(t).get("/v1/x")
        XCTAssertEqual(r.ok, true)
        XCTAssertEqual(t.requests.count, 2)
    }

    func testNonIdempotentRequestsAreNotRetried() async {
        let t = FakeTransport([(503, #"{"error":{"code":"unavailable","message":"Try later."}}"#, [:])])
        do {
            let _: OkDTO = try await client(t).post("/v1/x", body: Empty())
            XCTFail("expected error")
        } catch let e as APIError {
            XCTAssertEqual(e.code, "unavailable")
            XCTAssertEqual(e.message, "Try later.")
            XCTAssertTrue(e.retryable)
        } catch { XCTFail("\(error)") }
        XCTAssertEqual(t.requests.count, 1)
    }

    func testRefreshesTokenOnceOn401() async throws {
        let t = FakeTransport([(401, #"{"error":{"code":"invalid_token","message":"x"}}"#, [:]), (200, "{}", [:])])
        let tokens = Tokens()
        let _: OkDTO = try await client(t, tokens).get("/v1/me")
        XCTAssertEqual(tokens.refreshes, 1)
        XCTAssertEqual(t.requests.last?.value(forHTTPHeaderField: "authorization"), "Bearer t2")
    }

    func testTransportFailureIsOffline() async {
        let t = FakeTransport([])
        t.failNext = 5
        do {
            let _: OkDTO = try await client(t).get("/v1/me")
            XCTFail("expected offline")
        } catch is OfflineError {
            XCTAssertEqual(t.requests.count, 3) // idempotent: three attempts
        } catch { XCTFail("\(error)") }
    }

    /// The signature must verify over exactly the canonical string the server rebuilds.
    func testSignedRequestsVerifyOverTheCanonicalString() async throws {
        let t = FakeTransport([(201, "{}", [:])])
        let signer = TestSigner()
        let _: OkDTO = try await client(t, signer: signer).post("/v1/commands", body: ["capability": "nudge.send"], signed: true, idempotent: true)
        let r = try XCTUnwrap(t.requests.first)
        XCTAssertEqual(r.value(forHTTPHeaderField: "x-wakeme-device"), "dev-1")
        let ts = try XCTUnwrap(r.value(forHTTPHeaderField: "x-wakeme-timestamp"))
        let canonical = APIClient.canonical(method: "POST", path: "/v1/commands", timestamp: ts, body: r.httpBody ?? Data())
        XCTAssertTrue(canonical.hasPrefix("WAKEME-V1\nPOST\n/v1/commands\n\(ts)\n"))
        let sig = try P256.Signing.ECDSASignature(derRepresentation: XCTUnwrap(Data(base64Encoded: XCTUnwrap(r.value(forHTTPHeaderField: "x-wakeme-signature")))))
        XCTAssertTrue(signer.key.publicKey.isValidSignature(sig, for: Data(canonical.utf8)))
    }

    func testResumingSendsAnExplicitNull() async throws {
        let me = #"{"id":"u","displayName":"A","locale":"en","timeZone":"UTC","accountState":"active","settings":{"quietHours":{"enabled":false,"start":"22:00","end":"07:00"},"soundBudgetPerHour":6,"shareBypass":false},"billing":{"plan":"free","status":"none","credits":{"available":2,"introRemaining":2,"monthlyRemaining":0}},"limits":{"activeRoutines":{"used":0,"max":5},"customSounds":{"used":0,"max":3}}}"#
        let t = FakeTransport([(200, me, [:])])
        let api = WakeMeAPI(client: client(t))
        let decoded = try await api.setPaused(until: nil)
        XCTAssertEqual(decoded.displayName, "A")
        XCTAssertEqual(String(data: try XCTUnwrap(t.requests.first?.httpBody), encoding: .utf8), #"{"pausedUntil":null}"#)
    }

    func testOwnRoutineTermsOmitTheOwnerAndAreNotRetried() async throws {
        let t = FakeTransport([(503, #"{"error":{"code":"unavailable","message":"Try later."}}"#, [:])])
        let api = WakeMeAPI(client: client(t))
        let terms = WakeMeAPI.PlanTerms(label: "Class", localTime: "07:00", timeZone: "Europe/London", timeZonePolicy: "fixed", recurrence: .weekly([1, 2, 3, 4, 5]), soundId: "bundled:rise.gentle", ringSeconds: 90)
        do {
            _ = try await api.createPlan(terms)
            XCTFail("expected an error")
        } catch let e as APIError {
            XCTAssertEqual(e.code, "unavailable")
        }
        XCTAssertEqual(t.requests.count, 1, "a lost response must not create a second routine")
        let r = t.requests[0]
        XCTAssertEqual(r.httpMethod, "POST")
        XCTAssertEqual(r.url?.path, "/v1/alarm-plans")
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(r.httpBody)) as? [String: Any])
        XCTAssertNil(json["ownerId"], "own routine: no owner key at all (the server rejects null)")
        XCTAssertNil(json["followUpDelaySeconds"])
        XCTAssertEqual(json["localTime"] as? String, "07:00")
        XCTAssertEqual((json["recurrence"] as? [String: Any])?["kind"] as? String, "weekly")
        XCTAssertEqual(Set(json.keys), ["label", "localTime", "timeZone", "timeZonePolicy", "recurrence", "soundId", "ringSeconds"])
    }

    func testExportReturnsTheBodyAsSentAndDeletionSurfacesReauth() async throws {
        let export = #"{"format":"wakeme-export-v1","profile":{"displayName":"Sam"}}"#
        let t = FakeTransport([(200, export, [:]), (401, #"{"error":{"code":"reauth_required","message":"For your safety, sign in again to confirm this change."}}"#, [:])])
        let api = WakeMeAPI(client: client(t))
        let data = try await api.exportAccount()
        XCTAssertEqual(String(decoding: data, as: UTF8.self), export)
        do {
            _ = try await api.deleteAccount()
            XCTFail("expected reauth_required")
        } catch let e as APIError {
            XCTAssertEqual(e.code, "reauth_required")
        }
        XCTAssertEqual(t.requests.last?.httpMethod, "DELETE")
        XCTAssertEqual(t.requests.last?.url?.path, "/v1/account")
        XCTAssertEqual(t.requests.count, 2, "reauth_required is not a token refresh")
    }

    func testGrantBodiesCarryOnlyChosenLimitsAndBlockReportsOnlyWhenAsked() async throws {
        let grant = #"{"id":"g1","grantorId":"me","granteeId":"f1","version":1,"capabilities":["nudge.send"],"limits":{"nudge":{"maxPerHour":3,"windows":[{"days":[1,2,3,4,5,6,7],"start":"08:00","end":"22:00"}],"sounds":["bundled:quack.classic"],"overridesQuietHours":false}},"validFrom":"2026-10-19T12:00:00Z","validUntil":"2027-01-17T12:00:00Z","paused":false,"updatedAt":"2026-10-19T12:00:00Z"}"#
        let t = FakeTransport([(201, grant, [:]), (200, #"{"ok":true,"revokedGrants":1,"reportId":null}"#, [:]), (200, #"{"ok":true,"revokedGrants":0,"reportId":"r1"}"#, [:])])
        let api = WakeMeAPI(client: client(t))
        let limits = GrantLimits(nudge: NudgeLimits(maxPerHour: 3, windows: [LocalWindow(days: [1, 2, 3, 4, 5, 6, 7], start: "08:00", end: "22:00")], sounds: ["bundled:quack.classic"], overridesQuietHours: false))
        let g = try await api.createGrant(granteeId: "f1", capabilities: [Capability.nudge], limits: limits, validUntil: Instant.parse("2027-01-17T12:00:00Z"))
        XCTAssertEqual(g.version, 1)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(t.requests[0].httpBody)) as? [String: Any])
        XCTAssertEqual(Set(body.keys), ["granteeId", "capabilities", "limits", "validUntil"])
        XCTAssertEqual(Set((body["limits"] as? [String: Any] ?? [:]).keys), ["nudge"], "no null limits for capabilities not chosen (the server rejects unknown or null fields)")
        XCTAssertEqual(body["validUntil"] as? String, "2027-01-17T12:00:00Z")

        try await api.block("fs1", reportReason: nil)
        XCTAssertEqual(String(decoding: try XCTUnwrap(t.requests[1].httpBody), as: UTF8.self), "{}")
        try await api.block("fs1", reportReason: "unwanted_alerts")
        let blocked = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(t.requests[2].httpBody)) as? [String: Any])
        XCTAssertEqual((blocked["report"] as? [String: Any])?["reason"] as? String, "unwanted_alerts")
        XCTAssertEqual(t.requests[2].url?.path, "/v1/friendships/fs1/block")
    }

    func testQuizBodyMatchesTheChallengeSchema() async throws {
        let t = FakeTransport([(201, #"{"id":"c1","title":"Morning warm-up","publishedRevision":null,"revisions":[{"id":"r1","revision":1,"state":"draft","body":{}}]}"#, [:])])
        let api = WakeMeAPI(client: client(t))
        let draft = try await api.createQuiz(title: "Morning warm-up", questions: [
            .init(question: "What do we call our Thursday dinner?", answer: "Chaos curry", wrongOptions: ["Brunch", "brunch", "Chaos Curry"]),
            .init(question: "Which café is headquarters?", answer: "Lark"),
        ])
        XCTAssertNil(draft.latest?.definition, "a body this app can't run decodes as no definition, not an error")
        let sent = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(t.requests[0].httpBody)) as? [String: Any])
        let body = try XCTUnwrap(sent["body"] as? [String: Any])
        XCTAssertEqual(Set(body.keys), ["schemaVersion", "title", "kind", "difficulty", "maxDurationSeconds", "steps", "verification", "fallback", "permissionsRequired"])
        let steps = try XCTUnwrap(body["steps"] as? [[String: Any]])
        XCTAssertEqual(steps[0]["type"] as? String, "multiple_choice")
        let options = try XCTUnwrap(steps[0]["options"] as? [String])
        XCTAssertEqual(Set(options), ["Chaos curry", "Brunch"], "duplicates and the answer's look-alikes are dropped")
        XCTAssertEqual(options[try XCTUnwrap(steps[0]["correctIndex"] as? Int)], "Chaos curry")
        XCTAssertEqual(Set(steps[1].keys), ["type", "id", "prompt", "acceptedAnswers", "source"], "no null fields")
        XCTAssertEqual(steps[1]["source"] as? String, "creator_provided")
    }

    func testMessagingAndReportRequests() async throws {
        let t = FakeTransport([(200, #"{"ok":true}"#, [:]), (200, #"{"ok":true}"#, [:]), (200, #"{"ok":true}"#, [:]), (201, #"{"id":"r1"}"#, [:])])
        let api = WakeMeAPI(client: client(t))
        try await api.react("m1", emoji: "🦆")
        try await api.deleteMessage("m1")
        try await api.markRead("c1")
        try await api.report(targetKind: "message", targetId: "m1", reason: "harassment")
        XCTAssertEqual(t.requests.map { "\($0.httpMethod ?? "") \($0.url?.path ?? "")" },
                       ["POST /v1/messages/m1/reactions", "DELETE /v1/messages/m1", "POST /v1/conversations/c1/read", "POST /v1/reports"])
        let reaction = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(t.requests[0].httpBody)) as? [String: Any])
        XCTAssertEqual(reaction["emoji"] as? String, "🦆")
        let report = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(t.requests[3].httpBody)) as? [String: Any])
        XCTAssertEqual(Set(report.keys), ["targetKind", "targetId", "reason"], "no null note (the server rejects null fields)")
    }
}
