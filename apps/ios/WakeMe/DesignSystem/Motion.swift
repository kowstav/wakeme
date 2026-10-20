import SwiftUI

/// Motion tokens (packages/design-tokens, docs/design/system.md §5). Effortless in daily use,
/// joyful only at the quack and on success, and absent from alarm, safety and escape controls.
/// SwiftUI springs retarget from their current velocity, so every animation here is interruptible.
enum WMMotion {
    /// Pressed controls: fast and nearly critically damped.
    static var press: Animation { Animation.spring(response: WMMotionToken.pressResponse, dampingFraction: WMMotionToken.pressDamping) }
    /// Selection, toggles, small layout changes.
    static var snappy: Animation { Animation.spring(response: WMMotionToken.snappyResponse, dampingFraction: WMMotionToken.snappyDamping) }
    /// Larger surfaces and progress: no overshoot.
    static var smooth: Animation { Animation.spring(response: WMMotionToken.smoothResponse, dampingFraction: WMMotionToken.smoothDamping) }
    /// Reserved for the quack and celebrations: one visible overshoot, never a loop.
    static var playful: Animation { Animation.spring(response: WMMotionToken.playfulResponse, dampingFraction: WMMotionToken.playfulDamping) }
    /// Status and text swaps.
    static var standard: Animation { Animation.timingCurve(0.2, 0, 0, 1, duration: WMMotionToken.standard) }
    /// The Reduce Motion replacement for any movement: a short crossfade.
    static var reduced: Animation { Animation.easeInOut(duration: WMMotionToken.reducedCrossfade) }

    /// `animation` normally, the short crossfade with Reduce Motion on.
    static func respecting(_ reduceMotion: Bool, _ animation: Animation) -> Animation { reduceMotion ? reduced : animation }
}

/// The haptic vocabulary (tokens: haptics). The system haptics setting decides whether anything is
/// felt. Never used during an alarm or SOS beyond the system's own feedback.
enum WMHaptic {
    static var selection: SensoryFeedback { SensoryFeedback.selection }
    static var sent: SensoryFeedback { SensoryFeedback.success }
    static var failed: SensoryFeedback { SensoryFeedback.warning }
    static var wrongAnswer: SensoryFeedback { SensoryFeedback.impact(weight: .light) }
    static var completed: SensoryFeedback { SensoryFeedback.success }
}

/// Settles a pressed control slightly on a stiff spring and deepens its fill; with Reduce Motion
/// only the fill changes. Used by `PrimaryButton`, `SecondaryButton` and tappable cards.
struct WMPressStyle: ButtonStyle {
    var fill: Color = .clear
    var pressedFill: Color = .clear
    var shape: AnyShape = AnyShape(Capsule())
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? pressedFill : fill, in: shape)
            .contentShape(shape)
            .scaleEffect(configuration.isPressed && !reduceMotion ? WMMotionToken.pressScale : 1)
            .animation(WMMotion.respecting(reduceMotion, WMMotion.press), value: configuration.isPressed)
    }
}

/// First-appearance entrance for the `index`th block of a screen: fades in and rises 12 pt,
/// `WMMotionToken.stagger` after the previous block, capped at `staggerMaxItems` steps (about
/// 200 ms in total). Runs once; with Reduce Motion everything is simply there.
struct WMEntrance: ViewModifier {
    let index: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(shown || reduceMotion ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 12)
            .onAppear {
                guard !shown, !reduceMotion else { return }
                let delay = WMMotionToken.stagger * Double(min(index, WMMotionToken.staggerMaxItems - 1))
                withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: WMMotionToken.emphasized).delay(delay)) { shown = true }
            }
    }
}

extension View {
    func wmEntrance(_ index: Int) -> some View { modifier(WMEntrance(index: index)) }
}

/// The card-to-page zoom (iOS 18 `navigationTransition(.zoom)`), paired with
/// `matchedTransitionSource` on the source. Its Reduce Motion behaviour isn't documented, so with
/// Reduce Motion on the page uses the standard navigation transition instead.
struct WMZoomTransition: ViewModifier {
    let id: String
    let namespace: Namespace.ID
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if reduceMotion {
            content
        } else {
            content.navigationTransition(.zoom(sourceID: id, in: namespace))
        }
    }
}

extension View {
    func wmZoomTransition(id: String, in namespace: Namespace.ID) -> some View { modifier(WMZoomTransition(id: id, namespace: namespace)) }
}
