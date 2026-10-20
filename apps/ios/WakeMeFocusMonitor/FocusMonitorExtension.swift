import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings

/// Runs even when Wake Me isn't (ADR 0014): ends the shield when a focus session's time is up,
/// and puts it back when a break ends while the session is still on. Reads only what the app
/// stored in the shared App Group: the end time, whether it was ended, and the person's own
/// app selection.
final class FocusMonitorExtension: DeviceActivityMonitor {
    private let store = ManagedSettingsStore(named: .init("wakeme.focus"))
    private let shared = UserDefaults(suiteName: "group.app.wakeme.ios")

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        switch activity.rawValue {
        case "wakeme.focus.session":
            store.clearAllSettings()
        case "wakeme.focus.break":
            guard let shared, !shared.bool(forKey: "focus.ended"),
                  Date().timeIntervalSince1970 * 1000 < shared.double(forKey: "focus.endsAt"),
                  let data = shared.data(forKey: "focus.selection"),
                  let selection = try? JSONDecoder().decode(FamilyActivitySelection.self, from: data) else { return }
            store.shield.applications = selection.applicationTokens
            store.shield.applicationCategories = .specific(selection.categoryTokens)
        default:
            break
        }
    }
}
