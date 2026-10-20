import ManagedSettings

/// "Open Wake Me" opens the app for a break or "End focus" (iOS 26.5+, doc 06); earlier
/// versions close the shield and the person opens Wake Me themselves, as the shield says.
final class ShieldActionExtension: ShieldActionDelegate {
    override func handle(action: ShieldAction, for application: ApplicationToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(response(for: action))
    }

    override func handle(action: ShieldAction, for category: ActivityCategoryToken, completionHandler: @escaping (ShieldActionResponse) -> Void) {
        completionHandler(response(for: action))
    }

    private func response(for action: ShieldAction) -> ShieldActionResponse {
        guard action == .primaryButtonPressed else { return .close }
        if #available(iOS 26.5, *) { return .openParentalControlsApp }
        return .close
    }
}
