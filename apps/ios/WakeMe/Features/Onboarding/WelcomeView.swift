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

/// Doc 03 §5 step 1: the headline with an interactive quack. Tapping the duck (or "Play preview")
/// plays the quack on this phone only and the duck quacks with it. The blocks arrive with a short
/// stagger the first time; with Reduce Motion everything is simply there.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void
    @State private var quacks = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Button { quack() } label: { QuackBurst(tick: quacks, height: 88) }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Play a quack on this phone")
                    .wmEntrance(0)
                VStack(alignment: .leading, spacing: WMSpacing.xs) {
                    Text("Alert your friends. Wake each other up.")
                        .font(.largeTitle.bold())
                        .foregroundStyle(WMColor.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                    Text("From “where are you?” to “we’ve got this.”").foregroundStyle(WMColor.textSecondary)
                }
                .wmEntrance(1)
                WMCard {
                    VStack(alignment: .leading, spacing: WMLayout.titleGap) {
                        Text("Hear what a quack sounds like").font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary)
                        Text("Tap the duck or the button. This preview plays only on your phone.").foregroundStyle(WMColor.textSecondary)
                    }
                    SecondaryButton(title: "Play preview") { quack() }
                }
                .wmEntrance(2)
                Text("You decide what each friend can do. Nobody can ring your phone unless you allow it.")
                    .foregroundStyle(WMColor.textSecondary)
                    .wmEntrance(3)
            }
            .padding(.horizontal, WMLayout.screenMargin)
            .padding(.top, WMSpacing.xl)
            .padding(.bottom, WMSpacing.md)
            .frame(maxWidth: WMLayout.readableWidth)
            .frame(maxWidth: .infinity)
        }
        .background(WMColor.surface)
        // The story scrolls; the two ways in stay at the bottom, where a thumb reaches them.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: WMLayout.controlGap) {
                PrimaryButton(title: "I have an invite", action: onInvite)
                SecondaryButton(title: "Set up my own wake-up", action: onSelfSetup)
            }
            .padding(.horizontal, WMLayout.screenMargin)
            .padding(.vertical, WMSpacing.md)
            .frame(maxWidth: WMLayout.readableWidth)
            .frame(maxWidth: .infinity)
            .background(WMColor.surface)
            // Text scrolling under the pinned actions fades out instead of being cut mid-line.
            .overlay(alignment: .top) {
                LinearGradient(colors: [WMColor.surface.opacity(0), WMColor.surface], startPoint: .top, endPoint: .bottom)
                    .frame(height: WMSpacing.lg)
                    .offset(y: -WMSpacing.lg)
                    .allowsHitTesting(false)
            }
            .wmEntrance(4)
        }
    }

    private func quack() {
        QuackPreview.shared.play()
        quacks += 1
    }
}
