import CoreLocation
import Foundation
import Observation
import WakeMeAPI
import WakeMeCore

/// Sends this phone's position for one safety session the person started (never stealth):
/// iOS shows its location indicator while it runs, Safety shows who sees it and until when, and
/// Stop ends it at once. It also ends by itself when the session does. Same role as Android's
/// LocationShareService. If iOS ends the app, sending stops; Safety then offers to resume.
@MainActor @Observable
final class LocationSharer {
    static let shared = LocationSharer()

    struct Active: Equatable {
        let sessionId: String
        let endsAt: Date
        let viewerNames: [String]
        var lastSentAt: Date?
        var lastAccuracyMeters: Int?
    }

    private(set) var active: Active?
    @ObservationIgnored private var updates: Task<Void, Never>?
    @ObservationIgnored private var expiry: Task<Void, Never>?
    @ObservationIgnored private var background: CLBackgroundActivitySession?
    /// The server keeps only the latest sample and allows 120 an hour (Android: 45 s gap, 60 s updates).
    private static let minimumInterval: TimeInterval = 45
    /// Same as Android; the server refuses samples older than 15 minutes.
    private static let maximumFixAge: TimeInterval = 10 * 60

    func start(_ session: SafetySessionDTO, viewerNames: [String], api: WakeMeAPI) {
        stop()
        guard session.shareLocation, let endsAt = try? Instant.parse(session.endsAt), endsAt > Date() else { return }
        let id = session.id
        active = Active(sessionId: id, endsAt: endsAt, viewerNames: viewerNames)
        background = CLBackgroundActivitySession() // keeps updates coming while the app is in the background
        expiry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(max(0, endsAt.timeIntervalSinceNow)))
            if !Task.isCancelled { self?.finished(id) }
        }
        updates = Task { [weak self] in
            var lastPost = Date.distantPast
            do {
                sending: for try await update in CLLocationUpdate.liveUpdates() {
                    if Task.isCancelled || Date() >= endsAt { break }
                    // A fix without accuracy can't be labelled honestly, and an old fix is not a current position.
                    guard let location = update.location, location.horizontalAccuracy >= 0,
                          Date().timeIntervalSince(location.timestamp) <= Self.maximumFixAge,
                          Date().timeIntervalSince(lastPost) >= Self.minimumInterval else { continue }
                    lastPost = Date()
                    let accuracy = Int(location.horizontalAccuracy.rounded())
                    do {
                        try await api.postLocation(sessionId: id, capturedAt: location.timestamp, latitude: location.coordinate.latitude,
                                                   longitude: location.coordinate.longitude, accuracyMeters: accuracy)
                        self?.sent(id, accuracy: accuracy)
                    } catch let e as APIError where [403, 404, 409].contains(e.status) {
                        break sending // ended elsewhere, or this session doesn't share location
                    } catch {
                        // Offline or a server error: the next fix tries again; viewers see the last capture time.
                    }
                }
            } catch {}
            self?.finished(id)
        }
    }

    /// Stops sending at once (before the server hears about it, so it works offline).
    func stop() {
        updates?.cancel()
        expiry?.cancel()
        background?.invalidate()
        updates = nil
        expiry = nil
        background = nil
        active = nil
    }

    private func sent(_ id: String, accuracy: Int) {
        guard active?.sessionId == id else { return }
        active?.lastSentAt = Date()
        active?.lastAccuracyMeters = accuracy
    }

    private func finished(_ id: String) {
        if active?.sessionId == id { stop() }
    }
}

/// Asks for "While Using the App" location once, when someone chooses to share.
@MainActor
final class LocationPermission: NSObject, CLLocationManagerDelegate {
    static let shared = LocationPermission()
    private let manager = CLLocationManager()
    private var waiting: CheckedContinuation<Bool, Never>?

    var allowed: Bool { [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus) }

    /// True when sharing can go ahead; false if the person declined (now or earlier).
    func request() async -> Bool {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways: return true
        case .notDetermined: break
        default: return false
        }
        waiting?.resume(returning: false)
        return await withCheckedContinuation { continuation in
            waiting = continuation
            manager.delegate = self
            manager.requestWhenInUseAuthorization()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        guard status != .notDetermined else { return }
        Task { @MainActor in
            self.waiting?.resume(returning: status == .authorizedWhenInUse || status == .authorizedAlways)
            self.waiting = nil
        }
    }
}
