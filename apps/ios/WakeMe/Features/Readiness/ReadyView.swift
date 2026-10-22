import SwiftUI
import UserNotifications

/// "Make this phone ready" (doc 03 §5 steps 7–9): alarm authorization asked for here,
/// at wake setup; a real test alarm; then Ready or the exact next step.
struct ReadyView: View {
    @State private var authorization: AlarmAuthorization = WakeAlarms.shared.authorization
    @State private var testResult: String?
    @State private var working = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("Let's make sure your alarm rings")
                    .font(.largeTitle.bold())
                    .foregroundStyle(WMColor.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text("iPhone alarms ring even in silent mode or a Focus, once you allow them.")
                    .foregroundStyle(WMColor.textSecondary)
                // Step 1: alarm access. Once allowed it shrinks to its status, inside step 2's card.
                if authorization != .authorized {
                    WMCard {
                        stepHeader(icon: .alarm, family: WMPalette.lime, title: "Allow alarms")
                        switch authorization {
                        case .denied:
                            StatusPill(text: "Alarms are turned off for Wake Me", tone: .danger, icon: toneIcon(.danger))
                            Text("Turn them on in Settings → Wake Me → Alarms. You can still add friends and send quacks.")
                                .font(WMFont.supporting)
                                .foregroundStyle(WMColor.textSecondary)
                            SecondaryButton(title: "Open Settings", icon: .arrowUpRight) {
                                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                            }
                        default:
                            PrimaryButton(title: "Allow alarms") {
                                Task { authorization = await WakeAlarms.shared.requestAuthorization() }
                            }
                        }
                    }
                }
                if authorization == .authorized {
                    WMCard {
                        stepHeader(icon: .wake, family: WMPalette.gold, title: "Test alarm")
                        StatusPill(text: "Alarms allowed", tone: .success, icon: toneIcon(.success))
                        Text("Rings once in about a minute, on this phone only. Try it with headphones too.")
                            .font(WMFont.supporting)
                            .foregroundStyle(WMColor.textSecondary)
                        SecondaryButton(title: working ? "Setting…" : "Ring a test alarm in 1 minute", icon: .alarm) {
                            working = true
                            Task {
                                let r = await WakeAlarms.shared.scheduleTestAlarm()
                                working = false
                                switch r {
                                case .scheduled(let dates): testResult = "Set for \(dates[0].formatted(date: .omitted, time: .shortened)). Lock the phone and wait for it."
                                case .notAuthorized: testResult = "Alarms aren't allowed yet."
                                default: testResult = "The alarm couldn't be set. Try again."
                                }
                            }
                        }
                        // The result is the next step: lock the phone and listen.
                        if let testResult { Note(testResult, icon: .clock) }
                    }
                }
            }
            .padding(.horizontal, WMLayout.screenMargin).padding(.vertical, WMSpacing.md)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
        // The heading above is the screen's one visible title; the bar keeps the name for VoiceOver
        // and the back button without showing it a second time.
        .navigationTitle("Make this phone ready")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .principal) { EmptyView() } }
    }

    private func stepHeader(icon: WMIcon, family: WMFamily, title: String) -> some View {
        HStack(spacing: WMSpacing.sm) {
            IconChip(icon: icon, family: family)
            Text(title).font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
        }
    }
}
