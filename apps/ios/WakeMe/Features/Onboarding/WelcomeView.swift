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

/// Doc 03 §5 step 1: the headline with an interactive quack, laid out like the reference apps'
/// welcome: a large illustration, a condensed capital headline, one line under it, and the two ways
/// in pinned where a thumb reaches them (lime first, grey second). Tapping the duck (or "Play
/// preview") plays the quack on this phone only and the duck quacks with it. The blocks arrive with
/// a short stagger the first time; with Reduce Motion everything is simply there.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void
    @State private var quacks = 0

    var body: some View {
        ScrollView {
            VStack(spacing: WMSpacing.md) {
                Button { quack() } label: { QuackBurst(tick: quacks, height: 140) }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Play a quack on this phone")
                    .wmEntrance(0)
                VStack(spacing: WMSpacing.sm) {
                    DisplayText("Alert your friends. Wake each other up.", alignment: .center)
                    Text("From “where are you?” to “we’ve got this.”")
                        .foregroundStyle(WMColor.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .wmEntrance(1)
                // The preview plays only on this phone; the duck above does the same.
                PillButton(title: "Play preview", icon: .speaker) { quack() }
                    .wmEntrance(2)
                HStack(alignment: .top, spacing: WMSpacing.xs) {
                    WMIconImage(.lock, size: WMLayout.iconSizeSmall).foregroundStyle(WMColor.textSecondary)
                    Text("You decide what each friend can do. Nobody can ring your phone unless you allow it.")
                        .font(WMFont.supporting)
                        .foregroundStyle(WMColor.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
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
                PrimaryButton(title: "I have an invite", icon: .ticket, action: onInvite)
                SecondaryButton(title: "Set up my own wake-up", icon: .alarm, action: onSelfSetup)
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
