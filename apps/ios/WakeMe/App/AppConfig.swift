import Foundation

/// Non-secret client configuration from Info.plist (filled from build settings per
/// configuration; see project.yml). Publishable keys only; nothing here is a secret.
enum AppConfig {
    private static func value(_ key: String) -> String {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
    }

    static var apiBaseURL: URL { URL(string: value("WakeMeAPIBaseURL")) ?? URL(string: "http://127.0.0.1:8787")! }
    static var supabaseURL: String { value("WakeMeSupabaseURL").trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    static var supabaseAnonKey: String { value("WakeMeSupabaseAnonKey") }
    static var supabaseConfigured: Bool { !supabaseURL.isEmpty && !supabaseAnonKey.isEmpty }
    /// RevenueCat public (publishable) SDK key for the App Store; empty means billing is unavailable.
    static var revenueCatKey: String { value("WakeMeRevenueCatKey") }
    static var billingConfigured: Bool { !revenueCatKey.isEmpty }

    /// Debug builds without Supabase use the local dev backend's token issuer (services/api dev.ts).
    static var useDevAuth: Bool {
        #if DEBUG
        return !supabaseConfigured
        #else
        return false
        #endif
    }
}
