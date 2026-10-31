import SwiftUI

/// Clear account entry and an account-free alarm, with a decorative morning illustration.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void

    @ScaledMetric(relativeTo: .body) private var actionReserve: CGFloat = 390

    var body: some View {
        GeometryReader { space in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Your morning.\nYour rhythm.")
                            .font(WMFont.display).foregroundStyle(WMColor.textPrimary)
                            .accessibilityAddTraits(.isHeader)
                            .fixedSize(horizontal: false, vertical: true)
                        Text("A little nudge. A better day.")
                            .font(WMFont.body).foregroundStyle(WMColor.textSecondary)
                    }
                    Image("WakeDaylight").resizable().scaledToFit()
                        .frame(maxWidth: .infinity)
                        .frame(height: min(280, max(100, space.size.height - actionReserve)))
                        .accessibilityHidden(true)
                    VStack(spacing: 16) {
                        PrimaryButton(title: "Sign in", icon: .personPlus, action: onInvite)
                        SecondaryButton(title: "Set up my own wake-up", icon: .alarm, action: onSelfSetup)
                    }
                }
                .padding(24)
                .frame(maxWidth: WMLayout.readableWidth, alignment: .leading)
                .frame(maxWidth: .infinity, minHeight: space.size.height, alignment: .top)
            }
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
