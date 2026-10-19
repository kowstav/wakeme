import SwiftUI

/// One clear primary action per screen; at least 44 pt targets.
struct PrimaryButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .tint(WMColor.accent)
        .foregroundStyle(WMColor.textOnAccent)
        .clipShape(RoundedRectangle(cornerRadius: WMRadius.md))
    }
}

/// Outlined on the raised surface (same as Android's outlined button); never accent-tinted,
/// so it stays clearly secondary in dark mode.
struct SecondaryButton: View {
    let title: String
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .foregroundStyle(WMColor.textPrimary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, WMSpacing.md)
                .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
                .background(WMColor.surfaceRaised, in: RoundedRectangle(cornerRadius: WMRadius.md))
                .overlay(RoundedRectangle(cornerRadius: WMRadius.md).stroke(WMColor.border))
                .contentShape(RoundedRectangle(cornerRadius: WMRadius.md))
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.45)
    }
}

struct WMCard<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.sm) { content }
            .padding(WMSpacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WMColor.surfaceRaised, in: RoundedRectangle(cornerRadius: WMRadius.lg))
            .overlay(RoundedRectangle(cornerRadius: WMRadius.lg).stroke(WMColor.border))
    }
}

enum Tone { case neutral, info, success, warning, danger }

/// Status is always conveyed in words, never by colour alone.
struct StatusPill: View {
    let text: String
    var tone: Tone = .neutral
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8).accessibilityHidden(true)
            Text(text).font(.subheadline).foregroundStyle(WMColor.textPrimary)
        }
        .padding(.horizontal, WMSpacing.sm)
        .padding(.vertical, 6)
        .background(WMColor.surfaceSunken, in: Capsule())
    }
    private var color: Color {
        switch tone {
        case .neutral: WMColor.textSecondary
        case .info: WMColor.info
        case .success: WMColor.success
        case .warning: WMColor.warning
        case .danger: WMColor.danger
        }
    }
}

/// The signature quack acknowledgement (doc 03 §2): the duck hops once beside "Sent to Alex".
/// With Reduce Motion it simply appears. Literal copy: sent is not "heard" (the status pill
/// below says what the phone reported).
struct QuackSent: View {
    let name: String
    /// Changes on every send, replaying the hop.
    let tick: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hop = false

    var body: some View {
        HStack(spacing: WMSpacing.xs) {
            Text("🦆")
                .font(.title)
                .rotationEffect(.degrees(hop ? -12 : 0))
                .offset(y: hop ? -10 : 0)
                .accessibilityHidden(true)
            Text("Sent to \(name)").font(.headline).foregroundStyle(WMColor.textPrimary)
        }
        .accessibilityElement(children: .combine)
        .task(id: tick) {
            guard !reduceMotion else { return }
            withAnimation(.spring(response: 0.25, dampingFraction: 0.45)) { hop = true }
            try? await Task.sleep(for: .milliseconds(220))
            withAnimation(.spring(response: 0.35, dampingFraction: 0.6)) { hop = false }
        }
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
                    Text("🦆").font(.system(size: 56)).accessibilityHidden(true)
                }
                .ignoresSafeArea()
            }
        }
    }
}

extension View {
    func appSwitcherCover() -> some View { modifier(AppSwitcherCover()) }
}
