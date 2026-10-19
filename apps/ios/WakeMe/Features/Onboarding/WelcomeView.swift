import AVFAudio
import SwiftUI

/// Plays a bundled quack on this phone only. A preview never sends anything.
@MainActor
final class QuackPreview {
    static let shared = QuackPreview()
    private var player: AVAudioPlayer?

    func play() {
        guard let url = Bundle.main.url(forResource: "quack_classic", withExtension: "wav") else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
        player = try? AVAudioPlayer(contentsOf: url)
        player?.play()
    }
}

/// Doc 03 §5 step 1: the headline with a short interactive quack preview, then two routes.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text("🦆").font(.system(size: 64)).accessibilityHidden(true)
                Text("Alert your friends. Wake each other up.")
                    .font(.largeTitle.bold())
                    .foregroundStyle(WMColor.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                WMCard {
                    Text("Hear what a quack sounds like").font(.headline).foregroundStyle(WMColor.textPrimary)
                    Text("This preview plays only on your phone.").foregroundStyle(WMColor.textSecondary)
                    SecondaryButton(title: "Play preview") { QuackPreview.shared.play() }
                }
                Text("You decide what each friend can do. Nobody can ring your phone unless you allow it.")
                    .foregroundStyle(WMColor.textSecondary)
                PrimaryButton(title: "I have an invite", action: onInvite)
                SecondaryButton(title: "Set up my own wake-up", action: onSelfSetup)
            }
            .padding(WMSpacing.md)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
    }
}
