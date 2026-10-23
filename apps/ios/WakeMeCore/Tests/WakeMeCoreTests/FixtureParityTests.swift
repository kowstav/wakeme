import Foundation
import XCTest
@testable import WakeMeCore

/// Runs the server's golden fixtures (packages/policy-fixtures) against the
/// Swift implementation, so server, Android and iOS make identical decisions.
final class FixtureParityTests: XCTestCase {
    private static let dir: URL = {
        if let env = ProcessInfo.processInfo.environment["WAKEME_FIXTURES"] { return URL(fileURLWithPath: env) }
        // apps/ios/WakeMeCore/Tests/WakeMeCoreTests/<this file> → repository root
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../../../../packages/policy-fixtures/fixtures").standardized
    }()

    private func load(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: Self.dir.appendingPathComponent("\(name).json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func decode<T: Decodable>(_ type: T.Type, _ value: Any) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }

    /// Deep merge where an explicit null in the override replaces the base value.
    private func merge(_ base: Any, _ override: Any?) -> Any {
        guard let override else { return base }
        guard let o = override as? [String: Any], let b = base as? [String: Any] else { return override }
        var out = b
        for (k, v) in o { out[k] = v is NSNull ? NSNull() : merge(b[k] ?? NSNull(), v) }
        return out
    }

    private func cases(_ fx: [String: Any], _ key: String = "cases") throws -> [[String: Any]] {
        let list = try XCTUnwrap(fx[key] as? [[String: Any]])
        XCTAssertFalse(list.isEmpty, "\(key) is empty")
        return list
    }

    func testTimeWindows() throws {
        let fx = try load("time-windows")
        for c in try cases(fx) {
            let got = try isWithinWindow(Instant.parse(c["instant"] as! String), timeZone(c["timeZone"] as! String), decode(LocalWindow.self, c["window"]!))
            XCTAssertEqual(got, c["expected"] as? Bool, c["name"] as! String)
        }
        for c in try cases(fx, "quietCases") {
            let got = try isQuietTime(Instant.parse(c["instant"] as! String), timeZone(c["timeZone"] as! String), decode(QuietHours.self, c["quiet"]!))
            XCTAssertEqual(got, c["expected"] as? Bool, c["name"] as! String)
        }
    }

    func testOccurrences() throws {
        for c in try cases(try load("occurrences")) {
            let rule = c["rule"] as! [String: Any]
            let got = try nextOccurrences(
                rule["localTime"] as! String,
                timeZone(rule["timeZone"] as! String),
                decode(Recurrence.self, rule["recurrence"]!),
                after: Instant.parse(c["after"] as! String),
                count: c["count"] as! Int
            ).map { "\($0.localDate) \(Instant.format($0.instant))" }
            let expected = (c["expected"] as! [[String: Any]]).map { e -> String in
                // Fixtures may carry milliseconds; compare at second precision like the wire format.
                let instant = try! Instant.parse(e["instant"] as! String)
                return "\(e["localDate"] as! String) \(Instant.format(instant))"
            }
            XCTAssertEqual(got, expected, c["name"] as! String)
        }
    }

    func testLocalExecution() throws {
        let fx = try load("local-execution")
        for c in try cases(fx) {
            let m = merge(fx["base"]!, c["override"]) as! [String: Any]
            let cmd = m["command"] as! [String: Any]
            let grant: GrantSnapshot? = m["localGrant"] is NSNull ? nil : try decode(GrantSnapshot.self, m["localGrant"]!)
            let decision = try evaluateLocalExecution(
                now: Instant.parse(m["now"] as! String),
                command: IncomingCommand(
                    id: cmd["id"] as! String,
                    capability: cmd["capability"] as! String,
                    actorId: cmd["actorId"] as! String,
                    grantVersion: cmd["grantVersion"] as! Int,
                    expiresAt: cmd["expiresAt"] as! String,
                    soundId: cmd["soundId"] as? String,
                    ringSeconds: cmd["ringSeconds"] as? Int
                ),
                localGrant: grant,
                zone: timeZone(m["timeZone"] as! String),
                settings: decode(RecipientSettings.self, m["settings"]!),
                alreadyProcessed: m["alreadyProcessed"] as! Bool
            )
            let exp = c["expected"] as! [String: Any]
            let expected: LocalDecision = (exp["execute"] as! Bool) ? .execute(mode: exp["mode"] as! String) : .skip(reason: exp["reason"] as! String)
            XCTAssertEqual(decision, expected, c["name"] as! String)
        }
    }

    func testDeliveryState() throws {
        for c in try cases(try load("delivery-state")) {
            let name = c["name"] as! String
            let events = (c["events"] as! [[String: Any]]).map { DeliveryEvent(kind: $0["kind"] as! String, seq: Int64($0["seq"] as! Int), reason: $0["reason"] as? String) }
            let state = reduceDelivery(events)
            let exp = c["expected"] as! [String: Any]
            let es = exp["state"] as! [String: Any]
            XCTAssertEqual(state, DeliveryState(lifecycle: es["lifecycle"] as! String, transport: es["transport"] as! String, device: es["device"] as! String, human: es["human"] as! String, refusedReason: es["refusedReason"] as? String), name)
            let opts = c["options"] as! [String: Any]
            let sentAt = try Instant.parse("2026-10-19T14:00:00Z")
            let now = sentAt.addingTimeInterval(TimeInterval(opts["secondsSinceSent"] as! Int))
            XCTAssertEqual(displayStatus(state, now: now, sentAt: sentAt, kind: opts["kind"] as! String, shareBypass: opts["shareBypass"] as! Bool), exp["display"] as? String, name)
        }
    }

    func testChallengeVerification() throws {
        let fx = try load("challenge-verification")
        for c in try cases(fx, "normalizationCases") {
            XCTAssertEqual(normalizeAnswer(c["input"] as! String), c["expected"] as? String, c["input"] as! String)
        }
        for c in try cases(fx) {
            let step = try decode(ChallengeStep.self, c["step"]!)
            let r = c["response"] as! [String: Any]
            let response: StepResponse
            if let i = r["index"] as? Int { response = .index(i) }
            else if let t = r["text"] as? String { response = .text(t) }
            else if let o = r["order"] as? [String] { response = .order(o) }
            else if let m = r["move"] as? String { response = .move(m) }
            else if let e = r["elapsedSeconds"] as? NSNumber { response = .elapsed(e.doubleValue) }
            else { response = .checked(r["checked"] as! [Bool]) }
            XCTAssertEqual(verifyStep(step, response), c["expected"] as? Bool, c["name"] as! String)
        }
    }

    func testSignedEnvelopes() throws {
        let fx = try load("signed-envelopes")
        let keys = fx["trustedKeys"] as! [String: String]
        let all = try cases(fx)
        XCTAssertEqual(all.count, 7)
        func code(_ e: SignatureError) -> String {
            switch e {
            case .unsupportedAlgorithm: return "unsupported_algorithm"
            case .unknownKey: return "unknown_key"
            case .badEncoding: return "bad_encoding"
            case .digestMismatch: return "digest_mismatch"
            case .badSignature: return "bad_signature"
            case .revisionMismatch: return "revision_mismatch"
            }
        }
        for c in all {
            let name = c["name"] as! String
            let env = try decode(SignedEnvelope.self, c["envelope"]!)
            let exp = c["expected"] as! [String: Any]
            do {
                let p = try ChallengeEnvelopes.verify(env, trustedKeys: keys)
                XCTAssertTrue(exp["ok"] as! Bool, "\(name): expected \(exp["error"] ?? "")")
                XCTAssertEqual(p.revision, exp["revision"] as? Int, name)
                XCTAssertEqual(p.definition.steps.count, exp["steps"] as? Int, name)
            } catch let e as SignatureError {
                XCTAssertEqual(code(e), exp["error"] as? String, name)
            }
        }
    }
}
