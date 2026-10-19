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
}
