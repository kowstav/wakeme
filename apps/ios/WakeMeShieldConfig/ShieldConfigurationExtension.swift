import ManagedSettings
import ManagedSettingsUI
import UIKit

/// The shield's words (doc 03 §4E): the shared goal and the way on. Literal, never shaming.
final class ShieldConfigurationExtension: ShieldConfigurationDataSource {
    private func make() -> ShieldConfiguration {
        let shared = UserDefaults(suiteName: "group.app.wakeme.ios")
        let friend = shared?.string(forKey: "focus.friendName")
        let endsAt = (shared?.double(forKey: "focus.endsAt") ?? 0) / 1000
        let minutes = max(1, Int(ceil((endsAt - Date().timeIntervalSince1970) / 60)))
        return ShieldConfiguration(
            backgroundBlurStyle: .systemMaterial,
            title: .init(text: friend.map { "Focusing with \($0)" } ?? "You're focusing", color: .label),
            subtitle: .init(text: "\(minutes) min left. Open Wake Me to take a short break or to end focus. Ending is always possible.", color: .secondaryLabel),
            primaryButtonLabel: .init(text: "Open Wake Me", color: .label),
            secondaryButtonLabel: .init(text: "Close", color: .label)
        )
    }

    override func configuration(shielding application: Application) -> ShieldConfiguration { make() }
    override func configuration(shielding application: Application, in category: ActivityCategory) -> ShieldConfiguration { make() }
}
