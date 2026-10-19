import Foundation

// Same rules as the server (packages/policy) and Android (:core), checked by shared fixtures:
//  * windows start on listed ISO weekdays; end <= start continues into the next day
//  * DST gap: shift later by the gap length; DST fold: the earlier instant
// Foundation's own handling of nonexistent/ambiguous wall times is not relied on;
// `zonedToInstant` resolves them explicitly so all three platforms agree.

public enum TimeError: Error, Equatable {
    case invalidTime(String)
    case invalidDate(String)
    case invalidInstant(String)
    case invalidTimeZone(String)
}

/// Minutes since midnight for a strict 24-hour "HH:MM".
public func minuteOfDay(_ hhmm: String) throws -> Int {
    let u = Array(hhmm.utf8)
    guard u.count == 5, u[2] == UInt8(ascii: ":"),
          let h = Int(String(hhmm.prefix(2))), let m = Int(String(hhmm.suffix(2))),
          u[0] >= 48, u[0] <= 50, u[1] >= 48, u[1] <= 57, u[3] >= 48, u[3] <= 53, u[4] >= 48, u[4] <= 57,
          h <= 23, m <= 59 else { throw TimeError.invalidTime(hhmm) }
    return h * 60 + m
}

/// A Gregorian calendar fixed to one zone, for wall-clock fields.
func calendar(_ zone: TimeZone) -> Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = zone
    c.locale = Locale(identifier: "en_US_POSIX")
    return c
}

/// ISO weekday: Monday = 1 ... Sunday = 7.
func isoWeekday(_ date: Date, _ zone: TimeZone) -> Int {
    let w = calendar(zone).component(.weekday, from: date) // Sunday = 1
    return w == 1 ? 7 : w - 1
}

public func isWithinWindow(_ instant: Date, _ zone: TimeZone, _ window: LocalWindow) throws -> Bool {
    let parts = calendar(zone).dateComponents([.hour, .minute], from: instant)
    let now = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    let start = try minuteOfDay(window.start)
    let end = try minuteOfDay(window.end)
    let weekday = isoWeekday(instant, zone)
    if start < end { return window.days.contains(weekday) && now >= start && now < end }
    let previousDay = weekday == 1 ? 7 : weekday - 1
    return (window.days.contains(weekday) && now >= start) || (window.days.contains(previousDay) && now < end)
}

public func isWithinAnyWindow(_ instant: Date, _ zone: TimeZone, _ windows: [LocalWindow]) throws -> Bool {
    for w in windows where try isWithinWindow(instant, zone, w) { return true }
    return false
}

/// Whether a wall-clock time on an ISO weekday falls inside one of the windows: the same rule as
/// `isWithinWindow`, without a zone, for a time that will ring in someone else's zone (a wake-up
/// suggested for a friend, whose zone stays private).
public func isWithinAnyWindow(localTime: String, weekday: Int, _ windows: [LocalWindow]) throws -> Bool {
    let now = try minuteOfDay(localTime)
    let previousDay = weekday == 1 ? 7 : weekday - 1
    for w in windows {
        let start = try minuteOfDay(w.start)
        let end = try minuteOfDay(w.end)
        if start < end {
            if w.days.contains(weekday) && now >= start && now < end { return true }
        } else if (w.days.contains(weekday) && now >= start) || (w.days.contains(previousDay) && now < end) {
            return true
        }
    }
    return false
}

public func isQuietTime(_ instant: Date, _ zone: TimeZone, _ quiet: QuietHours) throws -> Bool {
    guard quiet.enabled else { return false }
    return try isWithinWindow(instant, zone, LocalWindow(days: Array(1...7), start: quiet.start, end: quiet.end))
}

/// A calendar date without a zone (yyyy-MM-dd).
public struct LocalDate: Equatable, Comparable, Sendable, CustomStringConvertible {
    public let year: Int, month: Int, day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    public init(_ iso: String) throws {
        let p = iso.split(separator: "-", omittingEmptySubsequences: false)
        guard iso.utf8.count == 10, p.count == 3, let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]),
              (1...12).contains(m), (1...31).contains(d) else { throw TimeError.invalidDate(iso) }
        self.init(year: y, month: m, day: d)
        guard self.adding(days: 0) == self else { throw TimeError.invalidDate(iso) }
    }

    /// Days since 1970-01-01 (proleptic Gregorian).
    var epochDay: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    init(epochDay z0: Int) {
        let z = z0 + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d)
    }

    public func adding(days: Int) -> LocalDate { LocalDate(epochDay: epochDay + days) }

    /// ISO weekday: Monday = 1 ... Sunday = 7. 1970-01-01 was a Thursday.
    public var isoWeekday: Int { ((epochDay % 7) + 7 + 3) % 7 + 1 }

    public var description: String { String(format: "%04d-%02d-%02d", year, month, day) }

    public static func < (a: LocalDate, b: LocalDate) -> Bool { a.epochDay < b.epochDay }

    /// The local date of an instant in a zone.
    public static func of(_ instant: Date, _ zone: TimeZone) -> LocalDate {
        let c = calendar(zone).dateComponents([.year, .month, .day], from: instant)
        return LocalDate(year: c.year!, month: c.month!, day: c.day!)
    }
}

public struct Occurrence: Equatable, Sendable {
    public let localDate: String
    public let instant: Date
}

/// The instant for a wall-clock time in a zone. In a DST gap the time moves
/// later by the gap length; in a fold the earlier instant wins (java.time rules).
public func zonedToInstant(_ date: LocalDate, _ hhmm: String, _ zone: TimeZone) throws -> Date {
    let minutes = try minuteOfDay(hhmm)
    // Wall-clock seconds as if the zone were UTC.
    let wall = TimeInterval(date.epochDay * 86_400 + minutes * 60)
    // Offsets well before and after cover the single transition a day can contain.
    let before = TimeInterval(zone.secondsFromGMT(for: Date(timeIntervalSince1970: wall - 86_400)))
    let after = TimeInterval(zone.secondsFromGMT(for: Date(timeIntervalSince1970: wall + 86_400)))
    var valid: [Date] = []
    for off in Set([before, after]) {
        let candidate = Date(timeIntervalSince1970: wall - off)
        if TimeInterval(zone.secondsFromGMT(for: candidate)) == off { valid.append(candidate) }
    }
    if let earliest = valid.min() { return earliest }
    // Gap: keep the pre-transition offset, which lands `gap` later on the new wall clock.
    return Date(timeIntervalSince1970: wall - before)
}

/// The next `count` occurrences strictly after `after`.
public func nextOccurrences(_ localTime: String, _ zone: TimeZone, _ recurrence: Recurrence, after: Date, count: Int) throws -> [Occurrence] {
    precondition((1...64).contains(count))
    if recurrence.kind == "once" {
        guard let iso = recurrence.date else { throw TimeError.invalidDate("missing") }
        let date = try LocalDate(iso)
        let instant = try zonedToInstant(date, localTime, zone)
        return instant > after ? [Occurrence(localDate: date.description, instant: instant)] : []
    }
    let days = recurrence.days ?? []
    if days.isEmpty { return [] }
    var out: [Occurrence] = []
    var date = LocalDate.of(after, zone).adding(days: -1)
    var i = 0
    while i < 8 * count + 2 && out.count < count {
        if days.contains(date.isoWeekday) {
            let instant = try zonedToInstant(date, localTime, zone)
            if instant > after { out.append(Occurrence(localDate: date.description, instant: instant)) }
        }
        date = date.adding(days: 1)
        i += 1
    }
    return out
}

// MARK: - ISO-8601 instants (the wire format: UTC "Z", optional fractional seconds)

public enum Instant {
    public static func parse(_ s: String) throws -> Date {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: s) { return d }
        throw TimeError.invalidInstant(s)
    }

    /// Matches java.time.Instant.toString / JS toISOString for whole seconds ("2026-10-19T14:00:00Z").
    public static func format(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f.string(from: d)
    }
}

public func timeZone(_ id: String) throws -> TimeZone {
    guard let z = TimeZone(identifier: id) else { throw TimeError.invalidTimeZone(id) }
    return z
}
