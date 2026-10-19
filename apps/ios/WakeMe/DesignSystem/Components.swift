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

struct SecondaryButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: WMSize.minTouchTarget)
        }
        .buttonStyle(.bordered)
        .foregroundStyle(WMColor.textPrimary)
        .clipShape(RoundedRectangle(cornerRadius: WMRadius.md))
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
