import Foundation
import XCTest
@testable import WakeMeCore

/// The `wm` dictionary the worker sends (services/api/src/worker/handlers.ts, dispatchCommand).
final class IncomingPushTests: XCTestCase {
    private let wire: [AnyHashable: Any] = [
        "aps": ["alert": ["title": "Alex", "body": "Quack! 🦆"], "sound": "quack_classic.wav"],
        "wm": ["t": "command", "id": "c1", "cap": "nudge.send", "actor": "u2", "gv": "3",
               "exp": "2026-10-20T08:02:00Z", "mode": "audible", "snd": "bundled:quack.classic"],
    ]

    func testACommandPushBecomesACommandToRevalidate() throws {
        let cmd = try XCTUnwrap(incomingCommand(fromPush: pushData(fromUserInfo: wire)))
        XCTAssertEqual(cmd, IncomingCommand(id: "c1", capability: "nudge.send", actorId: "u2", grantVersion: 3, expiresAt: "2026-10-20T08:02:00Z", soundId: "bundled:quack.classic"))
    }

    func testMalformedOrOtherPushesAreIgnored() {
        XCTAssertNil(incomingCommand(fromPush: [:]))
        XCTAssertNil(incomingCommand(fromPush: ["t": "sync"]))
        var noVersion = pushData(fromUserInfo: wire)
        noVersion["gv"] = "three"
        XCTAssertNil(incomingCommand(fromPush: noVersion))
        XCTAssertEqual(pushData(fromUserInfo: ["wm": ["ring": 60, "t": "command"]]), ["ring": "60", "t": "command"], "numbers arrive as strings")
    }

    func testReceiptsSayWhatThePhoneDid() {
        XCTAssertNil(receipt(for: .skip(reason: "duplicate")), "only the first delivery reports")
        XCTAssertEqual(receipt(for: .skip(reason: "expired"))?.kind, "execution_refused")
        XCTAssertEqual(receipt(for: .skip(reason: "needs_sync"))?.reason, "needs_sync")
        XCTAssertEqual(receipt(for: .execute(mode: "notify"))?.kind, "received")
    }

    func testAnExpiredQuackIsNeverReplayedAfterReconnecting() throws {
        let cmd = try XCTUnwrap(incomingCommand(fromPush: pushData(fromUserInfo: wire)))
        let grant = GrantSnapshot(id: "g", grantorId: "u1", granteeId: "u2", version: 3, capabilities: ["nudge.send"],
                                  limits: GrantLimits(nudge: NudgeLimits(maxPerHour: 3, windows: [LocalWindow(days: [1, 2, 3, 4, 5, 6, 7], start: "00:00", end: "23:59")], sounds: ["bundled:quack.classic"], overridesQuietHours: true)),
                                  validFrom: "2026-01-01T00:00:00Z", validUntil: "2027-01-01T00:00:00Z", paused: false)
        let settings = RecipientSettings(quietHours: QuietHours(enabled: false, start: "22:00", end: "07:00"))
        let late = try Instant.parse("2026-10-20T09:00:00Z")
        XCTAssertEqual(try evaluateLocalExecution(now: late, command: cmd, localGrant: grant, zone: TimeZone(identifier: "UTC")!, settings: settings, alreadyProcessed: false), .skip(reason: "expired"))
    }
}
