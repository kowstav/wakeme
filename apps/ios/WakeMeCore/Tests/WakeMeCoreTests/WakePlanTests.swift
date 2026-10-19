import Foundation
import XCTest
@testable import WakeMeCore

final class WakePlanTests: XCTestCase {
    private let routine = InstalledRoutine(
        planId: "p1", version: 2, label: "Class", localTime: "07:00", timeZone: "America/New_York", timeZonePolicy: "fixed",
        recurrence: .weekly([1, 2, 3, 4, 5]), soundId: "bundled:rise.gentle", ringSeconds: 90,
        challengeRevisionId: nil, followUpDelaySeconds: 180, paused: false
    )

    func testArmsEveryOccurrenceInTheWindowEachWithItsFollowUp() throws {
        let alarms = try scheduleFor(routine, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("Europe/London"))
        XCTAssertEqual(alarms[0].fireAt, try Instant.parse("2026-10-19T11:00:00Z"))
        XCTAssertEqual(alarms[1].role, .followUp)
        XCTAssertEqual(alarms[1].fireAt, try Instant.parse("2026-10-19T11:03:00Z"))
        // Sunday noon: weekdays through the next Monday, which is inside the 8-day window.
        let mains = alarms.filter { $0.role == .main }
        XCTAssertEqual(mains.map(\.occurrenceKey), ["2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-26"])
        XCTAssertEqual(mains.count, alarms.filter { $0.role == .followUp }.count)
        XCTAssertEqual(Set(alarms.map(\.stableId)).count, alarms.count)
    }

    func testWindowIsCappedAndAlwaysIncludesTheNextOccurrence() throws {
        var daily = routine
        daily.timeZone = "UTC"
        daily.recurrence = .weekly([1, 2, 3, 4, 5, 6, 7])
        daily.followUpDelaySeconds = nil
        let alarms = try scheduleFor(daily, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("UTC"))
        XCTAssertEqual(alarms.count, armMaxOccurrences)
        XCTAssertEqual(alarms.last?.fireAt, try Instant.parse("2026-10-26T07:00:00Z"))
        // A one-off weeks away is still armed.
        daily.recurrence = .once("2026-11-20")
        let once = try scheduleFor(daily, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("UTC"))
        XCTAssertEqual(once.map(\.fireAt), [try Instant.parse("2026-11-20T07:00:00Z")])
    }

    func testDeviceTimeZonePolicyFollowsThePhone() throws {
        var r = routine
        r.timeZonePolicy = "device"
        r.followUpDelaySeconds = nil
        let alarms = try scheduleFor(r, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("Asia/Tokyo"))
        XCTAssertEqual(alarms.first?.fireAt, try Instant.parse("2026-10-18T22:00:00Z"))
        XCTAssertTrue(alarms.allSatisfy { $0.role == .main })
    }

    func testPausedRoutinesInstallNothing() throws {
        var r = routine
        r.paused = true
        XCTAssertTrue(try scheduleFor(r, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("UTC")).isEmpty)
    }

    func testSystemStopIsNotCompletion() {
        let s = WakeSession(planId: "p1", occurrenceKey: "2026-10-19", state: .ringing, hasFollowUp: true).onSystemStop()
        XCTAssertEqual(s.state, .followUpPending)
        XCTAssertFalse(s.cancelsFollowUp)
        XCTAssertTrue(s.onChallengeCompleted().cancelsFollowUp)
        XCTAssertTrue(s.onEscape().cancelsFollowUp)
        XCTAssertEqual(s.onChallengeCompleted().onEscape().state, .completed)
    }

    /// The explicit gap/fold resolution, independent of the fixtures.
    func testDaylightSavingGapAndFold() throws {
        let ny = try timeZone("America/New_York")
        // 2026-03-08 02:30 does not exist in New York: moves later by the one-hour gap (03:30 EDT).
        XCTAssertEqual(Instant.format(try zonedToInstant(LocalDate("2026-03-08"), "02:30", ny)), "2026-03-08T07:30:00Z")
        // 2026-11-01 01:30 happens twice: the earlier (EDT) instant wins.
        XCTAssertEqual(Instant.format(try zonedToInstant(LocalDate("2026-11-01"), "01:30", ny)), "2026-11-01T05:30:00Z")
    }

    func testWallClockWindowsForAFriend() throws {
        let weekdayMornings = [LocalWindow(days: [1, 2, 3, 4, 5], start: "06:00", end: "10:00")]
        XCTAssertTrue(try isWithinAnyWindow(localTime: "07:00", weekday: 1, weekdayMornings))
        XCTAssertFalse(try isWithinAnyWindow(localTime: "10:00", weekday: 1, weekdayMornings), "the end is exclusive")
        XCTAssertFalse(try isWithinAnyWindow(localTime: "07:00", weekday: 6, weekdayMornings))
        // Overnight windows continue into the next day, as on the server.
        let fridayNight = [LocalWindow(days: [5], start: "22:00", end: "02:00")]
        XCTAssertTrue(try isWithinAnyWindow(localTime: "23:00", weekday: 5, fridayNight))
        XCTAssertTrue(try isWithinAnyWindow(localTime: "01:00", weekday: 6, fridayNight))
        XCTAssertFalse(try isWithinAnyWindow(localTime: "01:00", weekday: 5, fridayNight))
    }

    func testLocalDateArithmetic() throws {
        XCTAssertEqual(try LocalDate("2026-10-19").isoWeekday, 1) // Monday
        XCTAssertEqual(try LocalDate("2028-02-28").adding(days: 1).description, "2028-02-29")
        XCTAssertThrowsError(try LocalDate("2026-02-30"))
        XCTAssertThrowsError(try minuteOfDay("24:00"))
        XCTAssertThrowsError(try minuteOfDay("7:00"))
    }

    func testAlarmIDsAreStablePerOccurrenceAndRole() throws {
        let a = try scheduleFor(routine, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("UTC"))
        let b = try scheduleFor(routine, now: Instant.parse("2026-10-18T12:00:00Z"), deviceZone: timeZone("UTC"))
        XCTAssertEqual(a.map(\.alarmUUID), b.map(\.alarmUUID))
        XCTAssertNotEqual(a[0].alarmUUID, a[1].alarmUUID)
        XCTAssertEqual(a[0].alarmUUID.uuidString.dropFirst(14).first, "5") // version 5 layout
    }

    func testOnlyThePrimaryPhoneInstallsRoutines() {
        XCTAssertTrue(installsRoutines(primaryDeviceId: nil, thisDeviceId: nil))
        XCTAssertTrue(installsRoutines(primaryDeviceId: nil, thisDeviceId: "d1"))
        XCTAssertTrue(installsRoutines(primaryDeviceId: "d1", thisDeviceId: "d1"))
        XCTAssertFalse(installsRoutines(primaryDeviceId: "d1", thisDeviceId: "d2"))
        XCTAssertFalse(installsRoutines(primaryDeviceId: "d1", thisDeviceId: nil))
    }

    func testMathOverflowIsRejectedNotWrapped() {
        XCTAssertNil(evaluateMath([Int64.max, 1], "+"))
        XCTAssertEqual(evaluateMath([12, 4, 3], "/"), 1)
        XCTAssertNil(evaluateMath([7, 2], "/"))
    }
}
