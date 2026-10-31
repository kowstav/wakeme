import Foundation
import RevenueCat
import WakeMeAPI

/// A plan shown on the paywall, built only from live App Store product data. If anything needed
/// for honest copy is missing, the offer isn't shown (same rule as Android's Billing.kt).
struct Offer: Identifiable {
    let id: String
    let title: String
    /// e.g. "$34.99/year", from the store's localized price.
    let priceText: String
    /// e.g. "1 week free, then $34.99/year"; nil without an introductory offer.
    let trialText: String?
    let renewalTerms: String
    let isAnnual: Bool
    let package: Package
}

enum PurchaseOutcome {
    case completed(BillingStatusDTO)
    case cancelled
    case pending
    case failed(String)
}

/// StoreKit through RevenueCat (doc 02 §2). The app never decides entitlement itself: after a
/// purchase or restore it asks the server to re-verify with the store. Billing problems never
/// affect free features, alarms, stopping, the escape or revocation (CLAUDE.md invariants).
@MainActor
final class Billing {
    static let shared = Billing()
    private var configuredFor: String?

    /// The app user id is the Wake Me user id, so the server maps store events to the account.
    func configure(appUserId: String) async {
        guard AppConfig.billingConfigured, configuredFor != appUserId else { return }
        if !Purchases.isConfigured {
            Purchases.configure(withAPIKey: AppConfig.revenueCatKey, appUserID: appUserId)
        } else {
            _ = try? await Purchases.shared.logIn(appUserId)
        }
        configuredFor = appUserId
    }

    func offers() async -> [Offer] {
        guard AppConfig.billingConfigured, Purchases.isConfigured,
              let offering = try? await Purchases.shared.offerings().current else { return [] }
        return offering.availablePackages.compactMap(offer).sorted { $0.isAnnual && !$1.isAnnual }
    }

    private func periodText(_ p: SubscriptionPeriod?) -> String? {
        guard let p else { return nil }
        let unit: String
        switch p.unit {
        case .day: unit = "day"
        case .week: unit = "week"
        case .month: unit = "month"
        case .year: unit = "year"
        @unknown default: return nil
        }
        return p.value == 1 ? unit : "\(p.value) \(unit)s"
    }

    private func offer(_ package: Package) -> Offer? {
        let product = package.storeProduct
        guard let period = periodText(product.subscriptionPeriod) else { return nil }
        let price = product.localizedPriceString
        guard !price.isEmpty else { return nil }
        var trial: String?
        if let intro = product.introductoryDiscount, let introPeriod = periodText(intro.subscriptionPeriod) {
            trial = intro.paymentMode == .freeTrial
                ? "\(introPeriod.prefix(1).uppercased())\(introPeriod.dropFirst()) free, then \(price)/\(period)"
                : "\(intro.localizedPriceString) for \(introPeriod), then \(price)/\(period)"
        }
        let annual = product.subscriptionPeriod?.unit == .year
        return Offer(
            id: package.identifier,
            title: annual ? "Plus · yearly" : "Plus · monthly",
            priceText: "\(price)/\(period)",
            trialText: trial,
            renewalTerms: "Renews automatically at \(price)/\(period) until cancelled. Manage or cancel in your App Store subscriptions.",
            isAnnual: annual,
            package: package
        )
    }

    func purchase(_ offer: Offer, api: WakeMeAPI) async -> PurchaseOutcome {
        do {
            let result = try await Purchases.shared.purchase(package: offer.package)
            if result.userCancelled { return .cancelled }
            return .completed(try await api.billingSync())
        } catch let error as ErrorCode where error == .paymentPendingError {
            return .pending
        } catch {
            return .failed("The purchase didn't go through. You haven't been charged by Wake Me.")
        }
    }

    func restore(api: WakeMeAPI) async -> PurchaseOutcome {
        do {
            _ = try await Purchases.shared.restorePurchases()
            return .completed(try await api.billingSync())
        } catch {
            return .failed("Couldn't restore purchases right now. Free features keep working.")
        }
    }
}

/// What the subscription status means, in literal words (doc 07 §7D; same as Android).
func billingStatusText(_ s: BillingStatusDTO) -> (String, Tone) {
    let until = s.expiresAt.map { String($0.prefix(10)) }
    switch s.status {
    case "trial": return ("Plus · free trial" + (until.map { " until \($0)" } ?? ""), .info)
    case "active": return ("Plus · " + (s.willRenew == false ? "doesn't renew" : "renews") + (until.map { " \($0)" } ?? ""), .success)
    case "cancelled_active": return ("Plus · cancelled, access until \(until ?? "the end of the period")", .info)
    case "grace": return ("Plus · payment problem, still active while the store retries", .warning)
    case "billing_issue": return ("Plus · payment problem. Update payment in the App Store.", .warning)
    case "expired": return ("Free · Plus has ended", .neutral)
    case "refunded": return ("Free · Plus was refunded", .neutral)
    default: return ("Free", .neutral)
    }
}
