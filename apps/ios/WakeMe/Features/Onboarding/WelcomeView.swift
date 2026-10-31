import SwiftUI

/// The headline on the empty sky, the sun coming up behind the hills, and the two ways in on a
/// panel the hills run under. The scene gives its height back as text grows, and the whole page
/// scrolls on short windows.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void

    @ScaledMetric(relativeTo: .body) private var reserve: CGFloat = 340

    var body: some View {
        GeometryReader { space in
            let inset = space.safeAreaInsets.bottom
            ScrollView {
                VStack(spacing: 0) {
                    VStack(alignment: .leading, spacing: WMSpacing.xs) {
                        Text("Alert your friends.\nWake each other up.")
                            .font(WMFont.display).foregroundStyle(WMColor.textPrimary)
                            .accessibilityAddTraits(.isHeader)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("You decide what each friend can do")
                            .font(WMFont.body).foregroundStyle(WMColor.textSecondary)
                    }
                    .padding(.horizontal, WMLayout.screenMargin)
                    .padding(.top, WMSpacing.lg)
                    .frame(maxWidth: WMLayout.readableWidth, alignment: .leading)
                    .frame(maxWidth: .infinity)
                    Spacer(minLength: WMSpacing.md)
                    DawnScene()
                        .frame(height: min(480, max(140, space.size.height - reserve)))
                        .clipped()
                        .padding(.bottom, -WMRadius.xl)
                    VStack(spacing: WMSpacing.sm) {
                        PrimaryButton(title: "Sign in", icon: .personPlus, action: onInvite)
                        SecondaryButton(title: "Set up my own wake-up", icon: .alarm, action: onSelfSetup)
                    }
                    .padding(.horizontal, WMLayout.screenMargin)
                    .padding(.top, WMSpacing.lg)
                    .padding(.bottom, WMSpacing.md + inset)
                    .frame(maxWidth: WMLayout.readableWidth)
                    .frame(maxWidth: .infinity)
                    .background(WMColor.surfaceRaised, in: UnevenRoundedRectangle(topLeadingRadius: WMRadius.xl, topTrailingRadius: WMRadius.xl, style: .continuous))
                }
                .frame(minHeight: space.size.height + inset)
            }
            .ignoresSafeArea(.container, edges: .bottom)
            .background(WMColor.surface)
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
