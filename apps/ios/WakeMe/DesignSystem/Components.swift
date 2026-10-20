import SwiftUI
import WakeMeCore

/// One clear primary action per screen: the yellow capsule, 52 pt tall with a 17 pt semibold label
/// (both follow Dynamic Type). Pressing deepens the fill and settles the button slightly; with
/// Reduce Motion only the fill changes.
struct PrimaryButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(WMFont.cardTitle)
                .multilineTextAlignment(.center)
                .foregroundStyle(isEnabled ? WMColor.textOnAccent : WMColor.textPrimary.opacity(WMOpacity.disabled))
                .padding(.horizontal, WMLayout.buttonPaddingX)
                .padding(.vertical, WMSpacing.xs)
                .frame(maxWidth: .infinity, minHeight: WMLayout.buttonHeight)
        }
        .buttonStyle(WMPressStyle(fill: isEnabled ? WMColor.accent : WMColor.surfaceSunken, pressedFill: WMColor.accentPressed))
    }
}

/// Outlined with `outline` (≥ 3:1), so the button's edge is visible, not just its label; never
/// accent-tinted, so it stays clearly secondary in dark mode. Same height and label as
/// `PrimaryButton`, so paired actions line up and the fill alone says which one leads.
struct SecondaryButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(WMFont.cardTitle)
                .foregroundStyle(WMColor.textPrimary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, WMLayout.buttonPaddingX)
                .padding(.vertical, WMSpacing.xs)
                .frame(maxWidth: .infinity, minHeight: WMLayout.buttonHeight)
                .overlay(Capsule().stroke(isEnabled ? WMColor.outline : WMColor.border, lineWidth: 1))
        }
        .buttonStyle(WMPressStyle(fill: WMColor.surfaceRaised, pressedFill: WMColor.surfaceSunken))
        .opacity(isEnabled ? 1 : 0.45)
    }
}

/// A raised card: hairline border plus one soft ink shadow by day, a lighter tonal surface by night.
struct WMCard<Content: View>: View {
    var fill: Color = WMColor.surfaceRaised
    @ViewBuilder let content: Content
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.sm) { content }
            .padding(WMSpacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: WMRadius.lg))
            .overlay(RoundedRectangle(cornerRadius: WMRadius.lg).stroke(WMColor.border))
            .shadow(color: colorScheme == .dark ? .clear : WMColor.textPrimary.opacity(WMElevation.cardShadowOpacity),
                    radius: WMElevation.cardShadowRadius, y: WMElevation.cardShadowY)
    }
}

enum Tone { case neutral, info, success, warning, danger }

/// Status is always words plus a dot, never colour alone. The tinted pill makes the tone visible at
/// a glance; a change crossfades (no movement). With `announce`, VoiceOver speaks changes, for
/// statuses that change while the person waits (delivery, readiness).
struct StatusPill: View {
    let text: String
    var tone: Tone = .neutral
    var announce = false

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(dot).frame(width: 8, height: 8).accessibilityHidden(true)
            Text(text).font(WMFont.label).foregroundStyle(WMColor.textPrimary)
                .contentTransition(.opacity)
        }
        .padding(.horizontal, WMLayout.statusPillPaddingX)
        .padding(.vertical, WMLayout.statusPillPaddingY)
        .background(fill, in: Capsule())
        .animation(WMMotion.standard, value: text)
        .onChange(of: text) { _, new in
            if announce { UIAccessibility.post(notification: .announcement, argument: new) }
        }
        .onAppear {
            if announce { UIAccessibility.post(notification: .announcement, argument: text) }
        }
    }

    private var dot: Color {
        switch tone {
        case .neutral: WMColor.textSecondary
        case .info: WMColor.info
        case .success: WMColor.success
        case .warning: WMColor.warning
        case .danger: WMColor.danger
        }
    }

    private var fill: Color {
        switch tone {
        case .neutral: WMColor.surfaceSunken
        case .info: WMColor.infoContainer
        case .success: WMColor.successContainer
        case .warning: WMColor.warningContainer
        case .danger: WMColor.dangerContainer
        }
    }
}

/// A friend's monogram on one of six equal-lightness tints, chosen from a stable key so the same
/// friend always has the same colour. Decorative: the name beside it is what VoiceOver reads.
struct FriendAvatar: View {
    let name: String
    let key: String
    var size: CGFloat = WMLayout.avatarRow

    var body: some View {
        let tints = WMColor.avatarTints
        // Java's String.hashCode and floorMod, so a friend gets the same tint as on Android
        // (Swift's hashValue is randomised per launch).
        let hash = key.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
        let index = ((Int(hash) % tints.count) + tints.count) % tints.count
        Text(name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?")
            .font(.system(size: size * 0.42, weight: .bold))
            .foregroundStyle(WMColor.textPrimary)
            .frame(width: size, height: size)
            .background(tints[index], in: Circle())
            .accessibilityHidden(true)
    }
}

/// Sent → reached their phone → seen (quack) / rang (wake alarm). Steps fill only for evidence the
/// server reported (`DeliveryTrail`, shared fixture), and the fill animates only when that status
/// changes, never on a timer. Visual only: the literal status pill beside it is what VoiceOver
/// reads and announces, so nothing is said twice.
struct DeliveryTrailView: View {
    let displayStatus: String
    let alarm: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let trail = DeliveryTrail(displayStatus: displayStatus, alarm: alarm)
        let labels = ["Sent", "Reached their phone", alarm ? "Rang on their phone" : "They saw it"]
        VStack(spacing: WMSpacing.xs) {
            GeometryReader { geo in
                let r: CGFloat = 7
                let xs = [r, geo.size.width / 2, geo.size.width - r]
                let filled = trail.reached <= 1 ? xs[0] : xs[min(trail.reached, 3) - 1]
                ZStack(alignment: .leading) {
                    Capsule().fill(WMColor.border).frame(width: xs[2] - xs[0], height: 3).offset(x: xs[0])
                    Capsule().fill(WMColor.success).frame(width: max(0, filled - xs[0]), height: 3).offset(x: xs[0])
                    ForEach(0..<3, id: \.self) { i in
                        node(i, trail).frame(width: 2 * r, height: 2 * r).position(x: xs[i], y: geo.size.height / 2)
                    }
                }
                .animation(WMMotion.respecting(reduceMotion, WMMotion.smooth), value: trail.reached)
            }
            .frame(height: 18)
            HStack(alignment: .top) {
                ForEach(0..<3, id: \.self) { i in
                    Text(labels[i])
                        .font(.caption.weight(i == trail.reached - 1 ? .semibold : .regular))
                        .foregroundStyle(i < trail.reached ? WMColor.textPrimary : WMColor.textSecondary)
                        .multilineTextAlignment(i == 0 ? .leading : i == 1 ? .center : .trailing)
                        .frame(maxWidth: .infinity, alignment: i == 0 ? .leading : i == 1 ? .center : .trailing)
                }
            }
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder private func node(_ i: Int, _ trail: DeliveryTrail) -> some View {
        if i < trail.reached {
            Circle().fill(WMColor.success)
        } else if i == trail.reached, trail.state == .problem {
            ZStack {
                Circle().fill(WMColor.surfaceRaised)
                Circle().stroke(WMColor.warning, lineWidth: 2)
                Image(systemName: "xmark").font(.system(size: 7, weight: .heavy)).foregroundStyle(WMColor.warning)
            }
        } else {
            ZStack {
                Circle().fill(WMColor.surfaceRaised)
                Circle().stroke(WMColor.outline, lineWidth: 2)
            }
        }
    }
}

/// A calm empty state: the duck, what belongs here, and how to add the first one.
struct EmptyState: View {
    let title: String
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: WMSpacing.sm) {
            DuckMark(size: 44)
            VStack(alignment: .leading, spacing: WMSpacing.xxs) {
                Text(title).font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                Text(message).foregroundStyle(WMColor.textSecondary)
            }
        }
    }
}

/// "Sent to Alex" with the signature quack beside it. Literal copy: sent is not "heard" (the
/// status pill and trail below say what the phone reported).
struct QuackSent: View {
    let name: String
    /// Changes on every send, replaying the quack.
    let tick: Int

    var body: some View {
        HStack(spacing: WMSpacing.xs) {
            QuackBurst(tick: tick, height: 32)
            Text("Sent to \(name)").font(.headline).foregroundStyle(WMColor.textPrimary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Covers a sensitive screen (locations, private messages) while the app isn't active, so the
/// app switcher's snapshot doesn't show it (doc 02 §10, ADR 0012). Screenshots by the person
/// and VoiceOver are unaffected while the app is in use.
struct AppSwitcherCover: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.overlay {
            if scenePhase != .active {
                ZStack {
                    WMColor.surface
                    DuckMark(size: 64)
                }
                .ignoresSafeArea()
            }
        }
    }
}

extension View {
    func appSwitcherCover() -> some View { modifier(AppSwitcherCover()) }
}
