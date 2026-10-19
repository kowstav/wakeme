import Foundation
import Observation
import UIKit
import UserNotifications
import WakeMeAPI
import WakeMeCore

/// App-level routing and shared state. Mirrors the Android AppViewModel:
/// welcome → sign-in → profile → (self-setup readiness) → main. Invites never auto-accept.
@MainActor
@Observable
final class AppModel {
    enum Gate: Equatable { case loading, welcome, signIn, profile, ready, main, guest }

    var gate: Gate = .loading
    var me: MeDTO?
    var error: String?
    var offline = false

    let api = WakeMeAPI.app
    private let defaults = UserDefaults.standard
    private let selfSetupKey = "onboarding.selfSetup"
    private let guestKey = "onboarding.guest"
    /// The profile cache, also read by push handling when iOS wakes the app in the background.
    static let meCacheKey = "cache.me"
    private var meCacheKey: String { Self.meCacheKey }
    /// The running model, for push handling (which iOS calls outside SwiftUI).
    static weak var current: AppModel?

    func start() async {
        Self.current = self
        await Session.shared.restore()
        await route()
    }

    func chooseInvite() {
        defaults.set(false, forKey: selfSetupKey)
        gate = .signIn
    }

    /// Doc 03 §5 step 2: a personal wake-up needs no account (doc 02 §9 guest alarms).
    func chooseSelfSetup() {
        defaults.set(true, forKey: guestKey)
        gate = .guest
    }

    /// From guest use: sign in to add friends; wake-ups on this phone move to the account.
    func signInFromGuest() {
        gate = .signIn
    }

    /// Back from sign-in to where it came from.
    func backFromSignIn() {
        gate = defaults.bool(forKey: guestKey) ? .guest : .welcome
    }

    func route() async {
        guard await Session.shared.isSignedIn else {
            gate = gate == .signIn ? .signIn : defaults.bool(forKey: guestKey) ? .guest : .welcome
            return
        }
        do {
            updateMe(try await api.me())
            offline = false
        } catch let e as APIError where e.code == "profile_required" {
            gate = .profile
            return
        } catch is OfflineError {
            // Offline: keep working from the cached profile; local alarms never need the network.
            offline = true
            me = defaults.data(forKey: meCacheKey).flatMap { try? JSONDecoder().decode(MeDTO.self, from: $0) }
            if me == nil {
                error = "You're offline. Connect to finish setting up."
                gate = .welcome
                return
            }
        } catch {
            self.error = (error as? APIError)?.message ?? "Something went wrong. Try again."
            gate = .welcome
            return
        }
        await registerDevice()
        // Wake-ups set up before signing in become this account's routines (sync installs them).
        // Tried on every signed-in start, so any left by an offline sign-in move later.
        await LocalRoutines.shared.moveToAccount(api: api)
        defaults.set(false, forKey: guestKey)
        Task { await SyncEngine.shared.sync(self) } // routines changed elsewhere, outcomes recorded offline
        gate = defaults.bool(forKey: selfSetupKey) ? .ready : .main
    }

    func finishSelfSetup() {
        defaults.set(false, forKey: selfSetupKey)
        gate = .main
    }

    func requestCode(_ email: String) async -> Bool {
        do {
            try await Session.shared.requestEmailCode(email)
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func verifyCode(_ email: String, _ code: String) async {
        do {
            try await Session.shared.verifyEmailCode(email, code: code)
            await route()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func devSignIn() async {
        do {
            try await Session.shared.devSignIn()
            await route()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func createProfile(name: String, adult: Bool) async {
        guard adult else {
            error = "Wake Me is for people 18 and over."
            return
        }
        do {
            _ = try await api.createAccount(name: name.trimmingCharacters(in: .whitespaces), timeZone: TimeZone.current.identifier, locale: Locale.current.identifier.replacingOccurrences(of: "_", with: "-"))
            await route()
        } catch {
            self.error = (error as? APIError)?.message ?? "Couldn't create your profile. Try again."
        }
    }

    func refreshMe() async {
        if let fresh = try? await api.me() { updateMe(fresh) }
    }

    /// The profile as the server last confirmed it, kept for offline use.
    func updateMe(_ fresh: MeDTO) {
        me = fresh
        if let data = try? JSONEncoder().encode(fresh) { defaults.set(data, forKey: meCacheKey) }
    }

    /// iOS can launch the app in the background (app refresh, an alarm's stop button) without
    /// showing it: restore the session and cached profile so sync can run.
    func prepareForBackgroundWork() async {
        guard gate == .loading, me == nil else { return }
        await Session.shared.restore()
        me = defaults.data(forKey: meCacheKey).flatMap { try? JSONDecoder().decode(MeDTO.self, from: $0) }
    }

    func signOut() async {
        SyncEngine.shared.reset()
        for planId in WakeAlarms.shared.installedPlanIds() { WakeAlarms.shared.cancel(planId: planId) }
        await ChallengeStore.shared.clear()
        await ReceiptQueue.shared.clear()
        await GrantStore.shared.clear()
        LocalRoutines.shared.clear()
        defaults.removeObject(forKey: guestKey)
        PushCenter.forget()
        await Session.shared.signOut()
        DeviceKey.shared.setDeviceId(nil)
        defaults.removeObject(forKey: meCacheKey)
        me = nil
        gate = .welcome
    }

    /// Wake alarms ring only on the owner's primary phone; no primary yet counts as primary,
    /// so a single phone never silently skips its alarms.
    func isPrimaryPhone() async -> Bool {
        installsRoutines(primaryDeviceId: me?.primaryDeviceId, thisDeviceId: await DeviceKey.shared.deviceId())
    }

    func capabilities() async -> WakeMeAPI.DeviceCapabilities {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        return WakeMeAPI.DeviceCapabilities(
            notificationsAuthorized: settings.authorizationStatus == .authorized,
            timeSensitiveAuthorized: settings.timeSensitiveSetting == .enabled,
            alarmAuthorized: WakeAlarms.shared.authorization == .authorized,
            backgroundRefreshAvailable: UIApplication.shared.backgroundRefreshStatus == .available,
            speakerPreferenceSupported: false
        )
    }

    /// Registers this installation's Secure Enclave key (software key in the Simulator).
    func registerDevice() async {
        do {
            let device = try await api.registerDevice(
                installId: DeviceKey.shared.installId,
                osVersion: UIDevice.current.systemVersion,
                appBuild: (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "1",
                publicKeySPKIBase64: DeviceKey.shared.publicKeySPKIBase64(),
                push: PushCenter.currentPush(),
                capabilities: await capabilities(),
                makePrimary: me?.primaryDeviceId == nil
            )
            DeviceKey.shared.setDeviceId(device.id)
            PushCenter.markSent(PushCenter.currentPush() != nil)
            await GrantStore.shared.refresh(api: api)
            if me?.primaryDeviceId == nil, let fresh = try? await api.me() { updateMe(fresh) }
        } catch {
            // Registration is retried on the next launch; personal alarms don't depend on it.
        }
    }

    /// Reports what this phone can do after a permission changes (signed by the device).
    func reportCapabilities() async {
        guard let id = await DeviceKey.shared.deviceId() else { return }
        _ = try? await api.updateCapabilities(deviceId: id, await capabilities())
    }
}

/// Literal sender-facing copy for each server displayStatus. "Phone received" is not "they heard it".
func statusText(_ displayStatus: String) -> String {
    switch displayStatus {
    case "sending": "Sending…"
    case "sent": "Sent"
    case "no_confirmation_yet": "Sent · no confirmation from their phone yet"
    case "not_delivered": "Couldn’t reach their phone"
    case "phone_received": "Their phone received it"
    case "alarm_scheduled_on_phone": "Alarm scheduled on their phone"
    case "alarm_started": "Alarm started on their phone"
    case "not_played": "Their phone didn’t play it (their settings)"
    case "opened": "Opened"
    case "acknowledged": "They saw it 👍"
    case "challenge_completed": "Challenge completed (not proof they’re awake)"
    case "stopped": "Alarm stopped"
    case "ended_without_challenge": "Ended without the challenge"
    case "expired": "Expired before their phone confirmed"
    default: "Cancelled"
    }
}

/// Why an action isn't available, in words that don't blame or reveal private settings.
func reasonText(_ name: String, _ reason: String?) -> String {
    switch reason {
    case "capability_not_granted": "\(name) hasn't turned this on for you."
    case "grant_paused": "\(name) has paused requests from you."
    case "outside_allowed_window": "It's outside the hours \(name) chose."
    case "quiet_hours": "It's \(name)'s quiet time."
    case "recipient_paused": "\(name) isn't taking requests right now."
    case "recipient_not_ready": "\(name)'s phone isn't set up for this yet."
    case "feature_disabled": "This is temporarily unavailable."
    default: "Not available right now."
    }
}
