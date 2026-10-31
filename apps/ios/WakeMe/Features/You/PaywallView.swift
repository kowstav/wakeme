import StoreKit
import SwiftUI
import WakeMeAPI

/// Plus (doc 04 §11, §16; same copy and rules as Android's PaywallScreen): shown only when
/// opening a premium creation feature or from You, never during an alarm or before the first
/// paired interaction. Free basics, alarms, stopping, revocation and data controls never depend
/// on it. Today's charge and the renewal terms come from the store's own product data.
struct PaywallView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var offers: [Offer] = []
    @State private var selected: String?
    @State private var status: BillingStatusDTO?
    @State private var loading = true
    @State private var busy = false
    @State private var note: String?
    @State private var manage = false

    /// Mirrors PLAN_LIMITS / PLUS_MONTHLY_CREDITS in services/api/src/modules/billing/service.ts.
    private let plusRoutines = 30
    private let plusSounds = 30

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: WMSpacing.md) {
                    Text("Make your routine a team effort").font(WMFont.screenTitle).foregroundStyle(WMColor.textPrimary)
                    Text("Quacks, wake requests, safety check-ins and your alarms stay free.").foregroundStyle(WMColor.textSecondary)
                    benefit("Create with \(status?.credits.plusMonthlyAllowance ?? 10) AI challenge credits each month. Unused monthly credits don't carry over.")
                    benefit("Keep up to \(plusRoutines) active routines.")
                    benefit("Save up to \(plusSounds) custom sounds.")
                    if let status {
                        let (text, tone) = billingStatusText(status)
                        StatusPill(text: text, tone: tone)
                    }
                    if loading {
                        ProgressView().frame(maxWidth: .infinity)
                    } else if status?.plan == "plus" {
                        Text("You already have Plus on this account.").foregroundStyle(WMColor.textPrimary)
                    } else if offers.isEmpty {
                        StatusPill(text: "The App Store isn't available right now. Everything free keeps working.", tone: .warning)
                    } else {
                        ForEach(offers) { o in
                            Button { selected = o.id } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(o.title).font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary)
                                        Text(o.priceText).foregroundStyle(WMColor.textSecondary)
                                    }
                                    Spacer()
                                    Image(systemName: selected == o.id ? "checkmark.circle.fill" : "circle").accessibilityHidden(true)
                                }
                                .padding(WMSpacing.sm)
                                .frame(minHeight: WMSize.minTouchTarget)
                                .background(selected == o.id ? WMColor.accentContainer : WMColor.surfaceNeutral, in: RoundedRectangle(cornerRadius: WMRadius.md))
                                .overlay(RoundedRectangle(cornerRadius: WMRadius.md).stroke(selected == o.id ? WMColor.accentText : Color.clear, lineWidth: 2))
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected == o.id ? .isSelected : [])
                        }
                        if let chosen = offers.first(where: { $0.id == selected }) {
                            // Billing timeline: what is charged today, and what renews.
                            Text(chosen.trialText ?? "Charged today: \(chosen.priceText)").foregroundStyle(WMColor.textPrimary)
                            PrimaryButton(title: busy ? "Working…" : "Continue to the App Store") { Task { await buy(chosen) } }
                                .disabled(busy)
                            Text(chosen.renewalTerms).font(.footnote).foregroundStyle(WMColor.textSecondary)
                        }
                    }
                    if let note { Text(note).foregroundStyle(WMColor.textPrimary) }
                    Button("Restore purchases") { Task { await restore() } }.frame(minHeight: WMSize.minTouchTarget)
                    Button("Manage subscription") { manage = true }.frame(minHeight: WMSize.minTouchTarget)
                }
                .padding(.horizontal, WMLayout.screenMargin).padding(.vertical, WMSpacing.lg).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
            .background(WMColor.surface)
            .navigationTitle("Plus")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .manageSubscriptionsSheet(isPresented: $manage)
            .task { await load() }
        }
    }

    private func benefit(_ text: String) -> some View {
        HStack(alignment: .top, spacing: WMSpacing.xs) {
            WMIconImage(.check, size: WMLayout.iconSizeSmall).foregroundStyle(WMColor.accentText)
            Text(text).foregroundStyle(WMColor.textPrimary).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func load() async {
        if let me = app.me { await Billing.shared.configure(appUserId: me.id) }
        offers = await Billing.shared.offers()
        selected = offers.first?.id
        status = try? await app.api.billingStatus()
        loading = false
    }

    private func buy(_ offer: Offer) async {
        busy = true
        defer { busy = false }
        apply(await Billing.shared.purchase(offer, api: app.api))
    }

    private func restore() async {
        busy = true
        defer { busy = false }
        apply(await Billing.shared.restore(api: app.api))
    }

    private func apply(_ outcome: PurchaseOutcome) {
        switch outcome {
        case let .completed(s):
            status = s
            note = s.plan == "plus" ? "You're on Plus. Thank you." : "No active Plus subscription was found for this account."
            Task { await app.refreshMe() }
        case .cancelled: note = nil
        case .pending: note = "The purchase is waiting for approval. Plus starts when the App Store confirms it."
        case let .failed(message): note = message
        }
    }
}
