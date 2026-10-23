import XCTest
@testable import WakeMeCore

/// Same cases as the Kotlin FocusTest.
final class FocusTests: XCTestCase {
    private let t0: Int64 = 1_760_000_000_000
    private let min: Int64 = 60_000

    func testASessionIsBoundedByTheFriendsLimitAndFourHours() throws {
        XCTAssertEqual(try XCTUnwrap(startFocus(id: "a", now: t0, minutes: 45, limits: FocusLimits(maxSessionMinutes: 30, maxPerDay: 2, windows: []))).endsAt, t0 + 30 * min)
        XCTAssertEqual(try XCTUnwrap(startFocus(id: "b", now: t0, minutes: 600)).endsAt, t0 + 240 * min)
        XCTAssertNil(startFocus(id: "c", now: t0, minutes: 0))
    }

    func testTheShieldAppliesOnlyWhileActive() throws {
        let s = try XCTUnwrap(startFocus(id: "a", now: t0, minutes: 25))
        XCTAssertTrue(shouldShield(s, now: t0 + min))
        XCTAssertFalse(shouldShield(s, now: t0 + 25 * min), "ends by itself, offline")
        XCTAssertEqual(focusState(s, now: t0 + 26 * min), .finished(reason: "completed"))
        XCTAssertFalse(shouldShield(nil, now: t0))
    }

    func testAChallengeEarnsAShortBreakAtMostThreeTimes() throws {
        var s = try XCTUnwrap(startFocus(id: "a", now: t0, minutes: 60))
        let onBreak = try XCTUnwrap(takeBreak(s, now: t0 + 10 * min))
        XCTAssertFalse(shouldShield(onBreak, now: t0 + 12 * min))
        XCTAssertTrue(shouldShield(onBreak, now: t0 + 16 * min), "the break ends by itself")
        s = onBreak
        for i in 0..<2 { s = try XCTUnwrap(takeBreak(s, now: t0 + Int64(20 + i * 10) * min)) }
        XCTAssertNil(takeBreak(s, now: t0 + 50 * min), "no fourth break")
    }

    func testABreakNeverOutlastsTheSession() throws {
        let s = try XCTUnwrap(startFocus(id: "a", now: t0, minutes: 10))
        XCTAssertEqual(try XCTUnwrap(takeBreak(s, now: t0 + 8 * min)).breakUntil, s.endsAt)
    }

    func testEndFocusIsAlwaysAvailableAndImmediate() throws {
        let s = try XCTUnwrap(startFocus(id: "a", now: t0, minutes: 25))
        let ended = endFocus(s, now: t0 + 3 * min)
        XCTAssertEqual(focusState(ended, now: t0 + 3 * min), .finished(reason: "ended_early"))
        XCTAssertFalse(shouldShield(ended, now: t0 + 4 * min))
        XCTAssertEqual(endFocus(ended, now: t0 + 5 * min), ended)
        XCTAssertEqual(endFocus(s, now: t0 + 30 * min).endReason, "completed")
    }
}
