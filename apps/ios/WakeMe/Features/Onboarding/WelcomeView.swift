import SwiftUI

/// Clear account entry and an account-free alarm, with a decorative morning illustration.
struct WelcomeView: View {
    let onInvite: () -> Void
    let onSelfSetup: () -> Void

    @ScaledMetric(relativeTo: .largeTitle) private var welcomeTitleSize: CGFloat = 30

    var body: some View {
        GeometryReader { space in
            let sceneHeight = min(420, max(100, space.size.height - 380 * max(1, welcomeTitleSize / 30)))
            ScrollView {
                VStack(spacing: 0) {
                    Image("WakeMorning")
                        .resizable().scaledToFit()
                        .padding(12)
                        .frame(maxWidth: .infinity)
                        .frame(height: sceneHeight)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 28) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Better mornings,\ntogether.")
                                .font(.system(size: welcomeTitleSize, weight: .medium))
                                .tracking(-0.8)
                                .foregroundStyle(WMColor.textPrimary)
                                .accessibilityAddTraits(.isHeader)
                                .fixedSize(horizontal: false, vertical: true)
                            Text("Your alarms. Your people.")
                                .font(.body).foregroundStyle(WMColor.textSecondary)
                        }
                        VStack(spacing: 0) {
                            PrimaryButton(title: "Sign in", icon: .personPlus, action: onInvite)
                            Divider().padding(.vertical, 16)
                            Button(action: onSelfSetup) {
                                Text("Set up my own wake-up")
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(WMColor.textSecondary)
                                    .multilineTextAlignment(.center)
                                    .frame(maxWidth: .infinity, minHeight: 48)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(28)
                    .frame(maxWidth: .infinity, minHeight: max(0, space.size.height - sceneHeight), alignment: .topLeading)
                    .background(WMColor.surface, in: UnevenRoundedRectangle(topLeadingRadius: 32, topTrailingRadius: 32))
                }
                .frame(maxWidth: WMLayout.readableWidth)
                .frame(maxWidth: .infinity)
            }
            .background(LinearGradient(colors: [WMColor.brandInk.opacity(0.12), WMColor.surface],
                startPoint: .top, endPoint: .bottom))
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
