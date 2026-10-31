import SwiftUI
import WakeMeCore

/// Personal wake-ups without an account (doc 02 §9, doc 03 §5 step 2), mirroring Android's
/// GuestHome: make this phone ready, set wake-ups that live only here, and sign in when friends
/// should help. Nothing here needs the network.
struct GuestView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase
    @State private var routines: [InstalledRoutine] = []
    @State private var creating = false

    var body: some View {
        NavigationStack {
            List {
                Group {
                    if routines.isEmpty {
                        Section {
                            HeroCard {
                                HStack(alignment: .bottom) {
                                    Text("No wake-ups yet").font(WMFont.section).foregroundStyle(WMColor.onHero).accessibilityAddTraits(.isHeader)
                                    Spacer(minLength: WMSpacing.sm)
                                    DawnArc(localTime: "07:00", width: WMLayout.dawnArcHeroWidth, height: WMLayout.dawnArcHeroHeight)
                                }
                            }
                            .bentoRow()
                        }
                    } else {
                        Section("On this phone") {
                            ForEach(routines, id: \.planId) { r in
                                HeroCard {
                                HStack(alignment: .top, spacing: WMSpacing.sm) {
                                    VStack(alignment: .leading, spacing: WMSpacing.xs) {
                                        RoutineTimeLabel(time: r.localTime, days: daysText(r.recurrence))
                                        if !r.label.isEmpty { Text(r.label).foregroundStyle(WMColor.onHeroSecondary) }
                                        if WakeAlarms.shared.authorization == .authorized && WakeAlarms.shared.armedRoutine(planId: r.planId) != nil {
                                            StatusPill(text: "Ready on this phone", tone: .success, icon: toneIcon(.success))
                                        } else {
                                            StatusPill(text: "Not scheduled: allow alarms first", tone: .warning, icon: toneIcon(.warning))
                                        }
                                    }
                                    Spacer(minLength: 0)
                                    DawnArc(localTime: r.localTime)
                                }
                                }
                                .swipeActions { Button("Delete", role: .destructive) { LocalRoutines.shared.delete(r.planId); reload() } }
                            }
                        }
                    }
                    Section {
                        NavigationLink { ReadyView() } label: {
                            Label { Text("Make this phone ready") } icon: { IconChip(icon: .phone, family: WMPalette.lime, size: 32) }
                        }
                        .frame(minHeight: WMSize.minTouchTarget)
                    }
                    Section {
                        HStack(spacing: WMSpacing.sm) {
                            IconChip(icon: .friends, family: WMPalette.sky)
                            Text("Friends, challenges and check-ins").font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary)
                        }
                        .accessibilityElement(children: .combine)
                        SecondaryButton(title: "Sign in", icon: .personPlus) { app.signInFromGuest() }
                    }
                }
                .cardRows()
            }
            // The one action of this screen stays where a thumb reaches it.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                PrimaryButton(title: "New wake-up", icon: .plus) { creating = true }
                    .padding(.horizontal, WMLayout.screenMargin)
                    .padding(.vertical, WMSpacing.sm)
                    .frame(maxWidth: WMLayout.readableWidth)
                    .frame(maxWidth: .infinity)
                    .background(WMColor.surface)
            }
            .scrollContentBackground(.hidden)
            .background(WMColor.surface)
            .navigationTitle("Your wake-ups")
            .sheet(isPresented: $creating) {
                // Arms anything the first install missed (alarm access granted while it was saving).
                RoutineEditorView(guest: true) { Task { await armAndReload() } }
            }
            .task { await armAndReload() }
            .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await armAndReload() } } }
        }
    }

    private func reload() {
        routines = LocalRoutines.shared.all().sorted { $0.localTime < $1.localTime }
    }

    /// Alarm access may have been granted in Settings since a wake-up was saved.
    private func armAndReload() async {
        await LocalRoutines.shared.armMissing()
        reload()
    }
}
