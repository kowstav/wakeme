import UIKit
import UserNotifications
import WakeMeAPI
import WakeMeCore

/// APNs on iPhone (mirrors Android's WakeMeMessagingService + IncomingHandler).
///
/// - Registers for remote notifications and keeps the server's token for this device current.
/// - A friend's request is re-validated on this phone against the recipient's own cached grant
///   before the app shows or rings anything: a revoked or narrowed permission wins even if the
///   server already sent it, and an expired request is never replayed after reconnecting.
/// - Reports what actually happened ("received", "installed", refused with a reason, "opened",
///   "acknowledged") through the receipt queue, so the sender sees evidence, not guesses.
///
/// Limits (doc 02 §7): a quack's sound is rendered by iOS from the alert push when the app isn't
/// running, so local re-validation can't gate that case; it is bounded by the server's checks,
/// the short expiry and the recipient's approved sounds. A wake request also arrives as a
/// background update, which iOS may delay or drop; the visible alert is the fallback.
final class PushCenter: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    nonisolated static let tokenKey = "push.apnsToken"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories(Self.categories)
        application.registerForRemoteNotifications()
        return true
    }

    // MARK: token

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        Task { await Self.store(token: hex) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        #if DEBUG && targetEnvironment(simulator)
        // An unsigned Simulator build has no APNs entitlement. A stand-in token lets the dev
        // backend address this device; CI then relays its pushes with `xcrun simctl push`.
        Task { await Self.store(token: "simulator-" + DeviceKey.shared.installId.replacingOccurrences(of: "-", with: "")) }
        #endif
    }

    /// Saves the token and tells the server, when this phone is already registered.
    static func store(token: String) async {
        let previous = UserDefaults.standard.string(forKey: tokenKey)
        UserDefaults.standard.set(token, forKey: tokenKey)
        if token != previous { markSent(false) }
        guard !UserDefaults.standard.bool(forKey: tokenKey + ".sent"),
              let push = currentPush(),
              let deviceId = await DeviceKey.shared.deviceId() else { return }
        if (try? await WakeMeAPI.app.updatePush(deviceId: deviceId, push)) != nil { markSent(true) }
    }

    /// What device registration sends, if iOS has given this app a token yet.
    static func currentPush() -> WakeMeAPI.DevicePush? {
        guard let token = UserDefaults.standard.string(forKey: tokenKey) else { return nil }
        #if DEBUG
        let environment = "sandbox"
        #else
        let environment = "production"
        #endif
        return WakeMeAPI.DevicePush(token: token, environment: environment)
    }

    /// After registration carried the token, or at sign-out.
    static func markSent(_ sent: Bool) {
        UserDefaults.standard.set(sent, forKey: tokenKey + ".sent")
    }

    static func forget() {
        UserDefaults.standard.removeObject(forKey: tokenKey + ".sent")
    }

    // MARK: incoming

    /// Background updates: a wake request's native alarm path, or a hint to sync.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        let data = pushData(fromUserInfo: userInfo)
        switch data["t"] {
        case "command":
            _ = await IncomingRequests.handle(data, presenting: false)
            return .newData
        case "sync":
            await IncomingRequests.syncSoon()
            return .newData
        default:
            return .noData
        }
    }

    /// The app is open: re-validate first, and show nothing a revoked grant no longer allows.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let data = pushData(fromUserInfo: notification.request.content.userInfo)
        guard data["t"] == "command" else { return [.banner, .list, .sound] }
        switch await IncomingRequests.handle(data, presenting: true) {
        case .show: return [.banner, .list, .sound]
        case .showSilently: return [.banner, .list]
        case .hide: return []
        }
    }

    /// Taps and notification buttons.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let data = pushData(fromUserInfo: response.notification.request.content.userInfo)
        guard data["t"] == "command", let id = data["id"] else { return }
        // A tap is the first time the app sees a request that arrived while it wasn't running.
        if !(await GrantStore.shared.wasProcessed(id)) { _ = await IncomingRequests.handle(data, presenting: false) }
        switch response.actionIdentifier {
        case Self.ackAction, Self.okAction:
            await IncomingRequests.report(id, kind: "acknowledged")
        case UNNotificationDefaultActionIdentifier:
            await IncomingRequests.report(id, kind: "opened")
        default:
            break
        }
    }

    // MARK: categories (same actions as Android's notification buttons)

    nonisolated static let ackAction = "WM_ACK"
    nonisolated static let okAction = "WM_IM_OK"
    static var categories: Set<UNNotificationCategory> {
        let gotIt = UNNotificationAction(identifier: ackAction, title: "Got it", options: [])
        let imOK = UNNotificationAction(identifier: okAction, title: "I'm OK", options: [])
        return [
            UNNotificationCategory(identifier: "WM_NUDGE", actions: [gotIt], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: "WM_WAKE", actions: [gotIt], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: "WM_CHECKIN", actions: [imOK], intentIdentifiers: [], options: []),
        ]
    }
}

/// Decides what to do with one friend's request on this phone.
enum IncomingRequests {
    enum Presentation { case show, showSilently, hide }

    static func handle(_ data: [String: String], presenting: Bool) async -> Presentation {
        guard let cmd = incomingCommand(fromPush: data) else { return .hide }
        guard let me = await currentMe() else { return .hide }
        let first = await GrantStore.shared.firstTime(cmd.id)
        var grant = await GrantStore.shared.grant(from: me.id, to: cmd.actorId)
        if grant == nil || (grant?.version ?? 0) < cmd.grantVersion {
            // A grant changed since this phone last looked: fetch once, then decide.
            await GrantStore.shared.refresh(api: .app)
            grant = await GrantStore.shared.grant(from: me.id, to: cmd.actorId)
        }
        let settings = RecipientSettings(
            quietHours: QuietHours(enabled: me.settings.quietHours.enabled, start: me.settings.quietHours.start, end: me.settings.quietHours.end),
            pausedUntil: me.settings.pausedUntil,
            soundBudgetPerHour: me.settings.soundBudgetPerHour
        )
        let zone = TimeZone(identifier: me.timeZone) ?? .current
        let decision = (try? evaluateLocalExecution(now: Date(), command: cmd, localGrant: grant, zone: zone, settings: settings, alreadyProcessed: !first)) ?? .skip(reason: "not_authorized_locally")
        if let r = receipt(for: decision) { await report(cmd.id, kind: r.kind, reason: r.reason) }

        guard case let .execute(mode) = decision else {
            if decision == .skip(reason: "needs_sync") { await syncSoon() }
            // A duplicate of a request already shown stays as it was.
            return decision == .skip(reason: "duplicate") ? .show : .hide
        }
        switch cmd.capability {
        case Capability.alarmRequest:
            let name = data["title"].map { $0.replacingOccurrences(of: " is waking you up", with: "") } ?? ""
            if await WakeAlarms.shared.scheduleImmediate(commandId: cmd.id, soundId: cmd.soundId ?? "bundled:rise.gentle", friendName: name) {
                await report(cmd.id, kind: "installed")
                return presenting ? .showSilently : .hide // the alarm itself rings
            }
            await report(cmd.id, kind: "execution_refused", reason: WakeAlarms.shared.authorization == .authorized ? "os_scheduling_failed" : "permission_missing")
            return .show // the visible alert is the honest fallback
        case Capability.focus:
            // A request only (ADR 0014): it waits on the Focus screen until the recipient chooses.
            let friend = data["title"].map { $0.replacingOccurrences(of: " asks you to focus", with: "") } ?? "A friend"
            let request = FocusRequest(commandId: cmd.id, friendId: cmd.actorId, friendName: friend, minutes: data["min"].flatMap(Int.init) ?? 25)
            await MainActor.run { FocusController.shared.offer(request) }
            if presenting { await report(cmd.id, kind: "execution_reported") }
            return mode == "silent" ? .showSilently : .show
        default:
            if presenting { await report(cmd.id, kind: "execution_reported") }
            return mode == "silent" ? .showSilently : .show
        }
    }

    static func report(_ id: String, kind: String, reason: String? = nil) async {
        await ReceiptQueue.shared.enqueue(.init(commandId: id, kind: kind, reason: reason, at: Date()), api: .app)
    }

    @MainActor static func syncSoon() async {
        guard let model = AppModel.current else { return }
        await SyncEngine.shared.sync(model)
    }

    /// The signed-in profile, from memory or the offline cache (pushes can wake the app cold).
    @MainActor private static func currentMe() async -> MeDTO? {
        if let me = AppModel.current?.me { return me }
        await Session.shared.restore()
        guard await Session.shared.isSignedIn else { return nil }
        return UserDefaults.standard.data(forKey: AppModel.meCacheKey).flatMap { try? JSONDecoder().decode(MeDTO.self, from: $0) }
    }
}
