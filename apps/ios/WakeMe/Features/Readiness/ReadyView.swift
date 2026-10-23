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
                // Two steps, and the bar shows how close you are (it fills as each one is done).
                StepProgress(total: 2, done: authorization != .authorized ? 0 : testResult == nil ? 1 : 2)
                // Step 1: alarm access. Once allowed it shrinks to its status, inside step 2's card.
                if authorization != .authorized {
                    WMCard {
                        stepHeader(icon: .alarm, family: WMPalette.lime, title: "Alarm access")
                        switch authorization {
                        case .denied:
                            StatusPill(text: "Alarms are turned off for Wake Me", tone: .danger, icon: toneIcon(.danger))
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
                        if let testResult { Note(testResult) }
                    }
                }
            }
            .padding(.horizontal, WMLayout.screenMargin).padding(.vertical, WMSpacing.md)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
        // The heading above is the screen's one visible title, so the bar shows none (an empty
        // principal item did not hide it on iOS 26: ios run 45). The heading is a header for VoiceOver.
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func stepHeader(icon: WMIcon, family: WMFamily, title: String) -> some View {
        HStack(spacing: WMSpacing.sm) {
            IconChip(icon: icon, family: family)
            Text(title).font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
        }
    }
}
