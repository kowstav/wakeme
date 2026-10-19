import Foundation

/// How a friend's shared location reads to a viewer (same wording as Android's `locationLabel`).
public struct LocationLabel: Equatable, Sendable {
    public enum Tone: Sendable { case neutral, info, warning }
    public var text: String
    public var tone: Tone

    /// "Captured 12 minutes ago · accuracy about 80 m". Never presents an old sample as live;
    /// a sample older than 10 minutes is a warning. `nil` means not loaded yet.
    public init(_ latest: LatestLocationDTO?, shared: Bool = true) {
        guard shared else { self.init(text: "Check-in only · no location shared", tone: .neutral); return }
        switch latest?.status {
        case nil: self.init(text: "Checking for a location…", tone: .neutral)
        case "not_shared": self.init(text: "Check-in only · no location shared", tone: .neutral)
        case "no_location": self.init(text: "No location available", tone: .warning)
        default:
            let age = latest?.ageSeconds ?? 0
            let ago = switch age {
            case ..<60: "less than a minute ago"
            case ..<3600: "\(age / 60) minute\(age / 60 == 1 ? "" : "s") ago"
            default: "\(age / 3600) h \(age % 3600 / 60) min ago"
            }
            let accuracy = latest?.accuracyMeters.map { " · accuracy about \($0) m" } ?? ""
            self.init(text: "Captured \(ago)\(accuracy)", tone: age > 10 * 60 ? .warning : .info)
        }
    }

    public init(text: String, tone: Tone) {
        self.text = text
        self.tone = tone
    }
}
