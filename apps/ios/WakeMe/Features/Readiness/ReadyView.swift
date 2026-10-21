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
                Text("Wake Me uses iPhone alarms, which ring even in silent mode or a Focus once you allow them. Then set a test alarm to hear it here.")
                    .foregroundStyle(WMColor.textSecondary)
                WMCard {
                    switch authorization {
                    case .authorized:
                        StatusPill(text: "Alarms allowed", tone: .success)
                    case .denied:
                        StatusPill(text: "Alarms are turned off for Wake Me", tone: .danger)
                        Text("Turn them on in Settings → Wake Me → Alarms. You can still add friends and send quacks.")
                            .foregroundStyle(WMColor.textSecondary)
                        SecondaryButton(title: "Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    case .notDetermined:
                        StatusPill(text: "Alarms not set up yet", tone: .warning)
                        PrimaryButton(title: "Allow alarms") {
                            Task { authorization = await WakeAlarms.shared.requestAuthorization() }
                        }
                    }
                }
                if authorization == .authorized {
                    WMCard {
                        Text("Test alarm").font(.headline).foregroundStyle(WMColor.textPrimary)
                        Text("Rings once in about a minute on this phone only. Try it with headphones connected too.")
                            .foregroundStyle(WMColor.textSecondary)
                        SecondaryButton(title: working ? "Setting…" : "Ring a test alarm in 1 minute") {
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
                        if let testResult { Text(testResult).foregroundStyle(WMColor.textPrimary) }
                    }
                    StatusPill(text: testResult == nil ? "Next step: ring a test alarm" : "Ready on this phone once you've heard it", tone: testResult == nil ? .info : .success)
                }
            }
            .padding(.horizontal, WMLayout.screenMargin).padding(.vertical, WMSpacing.md)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
        .navigationTitle("Make this phone ready")
        .navigationBarTitleDisplayMode(.inline)
    }
}
