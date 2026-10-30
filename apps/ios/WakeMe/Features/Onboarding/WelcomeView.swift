import SwiftUI

/// Clear account entry and an account-free alarm, with a decorative morning illustration.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void

    var body: some View {
        GeometryReader { space in
            ScrollView {
                VStack(spacing: WMSpacing.lg) {
                    Text("WakeMe").font(WMFont.section).foregroundStyle(WMColor.brandInk)
                    Image("WakeMorning").resizable().scaledToFit().frame(height: min(360, max(180, space.size.height - 220))).accessibilityHidden(true)
                        .wmEntrance(0)
                    DisplayText("Better mornings.\nTogether.", alignment: .center)
                        .wmEntrance(1)
                }
                .padding(.horizontal, WMLayout.screenMargin)
                .padding(.vertical, WMSpacing.lg)
                .frame(maxWidth: WMLayout.readableWidth)
                // Centred in the space above the actions; at large text sizes it simply scrolls.
                .frame(maxWidth: .infinity, minHeight: space.size.height)
            }
        }
        .background(WMColor.surface)
        // The story scrolls; the two ways in stay at the bottom, where a thumb reaches them.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: WMLayout.controlGap) {
                PrimaryButton(title: "Sign in", icon: .personPlus, action: onInvite)
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
}

/// Five friends' faces in a slight arc, the middle one largest. Tapping a face plays its call here
/// and gives it a little hop (with Reduce Motion, only the sound).
struct FriendHuddle: View {
    private struct Spot: Hashable {
        let sound: String
        let size: CGFloat
        let lift: CGFloat
    }

    private let huddle: [Spot] = [
        Spot(sound: "bundled:cat.meow", size: 64, lift: 10), Spot(sound: "bundled:frog.ribbit", size: 76, lift: 2),
        Spot(sound: "bundled:dog.woof", size: 92, lift: 0), Spot(sound: "bundled:monkey.hoot", size: 76, lift: 2),
        Spot(sound: "bundled:cow.moo", size: 64, lift: 10),
    ]
    @State private var hopping: String?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .bottom, spacing: -10) {
            ForEach(huddle, id: \.self) { f in
                if let sound = AnimalSounds.sound(f.sound) {
                    Button {
                        SoundPreview.shared.play(sound.id)
                        hopping = sound.id
                        Task { try? await Task.sleep(for: .milliseconds(220)); if hopping == sound.id { hopping = nil } }
                    } label: {
                        sound.face.image
                            .resizable()
                            .scaledToFit()
                            .frame(width: f.size, height: f.size)
                            .offset(y: -f.lift - (hopping == sound.id && !reduceMotion ? 10 : 0))
                            .animation(WMMotion.respecting(reduceMotion, WMMotion.standard), value: hopping)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(sound.name)
                }
            }
        }
        .padding(.top, WMSpacing.md)
        .frame(maxWidth: .infinity)
    }
}
