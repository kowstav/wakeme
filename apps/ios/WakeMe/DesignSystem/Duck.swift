import SwiftUI

/// The Wake Me duck, drawn from the same 64-unit geometry as Android `DuckMark` and the design
/// directions page (docs/design/system.md §6). Original artwork instead of the 🦆 emoji, which looks
/// different on every OS version and can't open its beak. Always decorative: hidden from VoiceOver,
/// because the words beside it carry the meaning.
struct DuckMark: View {
    var size: CGFloat = 48
    var beakOpen: Double = 0

    var body: some View {
        DuckFigure(frame: QuackFrame(beak: beakOpen), showsWaves: false)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// The signature quack: the duck hops once, opens its beak, and three sound marks ripple out and
/// fade, in `WMMotionToken.quack`. It replays only when `tick` changes (a send the server accepted)
/// and says nothing about delivery: the trail below it does. With Reduce Motion it is a still duck
/// mid-quack. At rest (tick 0) it is a calm duck with a closed beak.
struct QuackBurst: View {
    let tick: Int
    var height: CGFloat = 56
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if reduceMotion {
                DuckFigure(frame: tick > 0 ? QuackFrame(beak: 0.7, still: true) : QuackFrame(), showsWaves: true)
            } else {
                KeyframeAnimator(initialValue: QuackFrame(), trigger: tick) { frame in
                    DuckFigure(frame: frame, showsWaves: true)
                } keyframes: { _ in
                    KeyframeTrack(\.hop) {
                        CubicKeyframe(1, duration: WMMotionToken.quack * 0.22)
                        CubicKeyframe(0, duration: WMMotionToken.quack * 0.23)
                    }
                    KeyframeTrack(\.beak) {
                        LinearKeyframe(0, duration: WMMotionToken.quack * 0.08)
                        LinearKeyframe(1, duration: WMMotionToken.quack * 0.12)
                        LinearKeyframe(1, duration: WMMotionToken.quack * 0.18)
                        LinearKeyframe(0, duration: WMMotionToken.quack * 0.14)
                    }
                    KeyframeTrack(\.waves) {
                        MoveKeyframe(0)
                        LinearKeyframe(1, duration: WMMotionToken.quack)
                    }
                }
            }
        }
        // 80 × 64 units: the duck plus room for the sound marks to its right.
        .frame(width: height * 1.25, height: height)
        .accessibilityHidden(true)
    }
}

/// One frame of the quack. `waves` runs 0 → 1 across the animation; 1 means no marks showing.
struct QuackFrame: Sendable {
    var hop: Double = 0
    var beak: Double = 0
    var waves: Double = 1
    var still = false
}

/// Draws the duck (and, for the quack, its sound marks) in a 64-unit coordinate space.
struct DuckFigure: View {
    let frame: QuackFrame
    let showsWaves: Bool

    var body: some View {
        Canvas { context, size in
            let u = size.height / 64
            context.scaleBy(x: u, y: u)
            var duck = context
            duck.translateBy(x: 32, y: 46)
            duck.rotate(by: .degrees(-7 * frame.hop))
            duck.translateBy(x: -32, y: CGFloat(-46 - 5 * frame.hop))
            let feathers = GraphicsContext.Shading.color(WMColor.accent)
            duck.fill(DuckPaths.tail, with: feathers)
            duck.fill(Path(ellipseIn: CGRect(x: 9, y: 28, width: 42, height: 28)), with: feathers)
            duck.fill(Path(ellipseIn: CGRect(x: 30, y: 10, width: 24, height: 24)), with: feathers)
            duck.fill(DuckPaths.wing, with: .color(WMColor.accentPressed))
            // The lower beak hinges at the corner of the mouth and swings down as it opens.
            let open = CGFloat(min(max(frame.beak, 0), 1))
            let hinge = CGAffineTransform(translationX: 51, y: 24.5)
                .rotated(by: open * 24 * .pi / 180)
                .translatedBy(x: -51, y: -24.5)
            duck.fill(DuckPaths.lowerBeak.applying(hinge), with: .color(WMIllustration.duckBeakShade))
            duck.fill(DuckPaths.upperBeak, with: .color(WMIllustration.duckBeak))
            duck.fill(Path(ellipseIn: CGRect(x: 42.7, y: 16.7, width: 4.6, height: 4.6)), with: .color(WMIllustration.duckEye))
            duck.fill(Path(ellipseIn: CGRect(x: 45.05, y: 17.45, width: 1.5, height: 1.5)), with: .color(WMIllustration.duckEyeHighlight))

            guard showsWaves else { return }
            for i in 0..<3 {
                let alpha = frame.still ? 1 - 0.25 * Double(i) : Self.waveAlpha(frame.waves, i)
                guard alpha > 0 else { continue }
                let r = (6 + 4.5 * Double(i)) * (frame.still ? 1 : Self.waveGrowth(frame.waves, i))
                context.stroke(Self.arc(radius: r), with: .color(WMColor.accentText.opacity(alpha)), style: StrokeStyle(lineWidth: 2.6, lineCap: .round))
            }
        }
    }

    /// A right-facing arc around the beak tip, built point by point (no clockwise ambiguity).
    private static func arc(radius r: Double) -> Path {
        var p = Path()
        for step in 0...12 {
            let a = (-38 + 76 * Double(step) / 12) * .pi / 180
            let point = CGPoint(x: 62 + r * cos(a), y: 24 + r * sin(a))
            if step == 0 { p.move(to: point) } else { p.addLine(to: point) }
        }
        return p
    }

    private static func waveStart(_ i: Int) -> Double { 0.12 + 0.1 * Double(i) }
    private static func waveGrowth(_ p: Double, _ i: Int) -> Double { 0.75 + 0.25 * min(max((p - waveStart(i)) / 0.5, 0), 1) }
    private static func waveAlpha(_ p: Double, _ i: Int) -> Double {
        let t = (p - waveStart(i)) / 0.5
        if t <= 0 || t >= 1 { return 0 }
        return t < 0.3 ? t / 0.3 : 1 - (t - 0.3) / 0.7
    }
}

private enum DuckPaths {
    static var tail: Path { Path { p in p.move(to: .init(x: 12, y: 37)); p.addLine(to: .init(x: 5, y: 25)); p.addQuadCurve(to: .init(x: 19, y: 32), control: .init(x: 13, y: 27)); p.closeSubpath() } }
    static var wing: Path { Path { p in p.move(to: .init(x: 20, y: 40)); p.addQuadCurve(to: .init(x: 40, y: 40), control: .init(x: 30, y: 34)); p.addQuadCurve(to: .init(x: 22, y: 46), control: .init(x: 32, y: 48)); p.closeSubpath() } }
    static var upperBeak: Path { Path { p in p.move(to: .init(x: 51, y: 18.5)); p.addQuadCurve(to: .init(x: 62.5, y: 22), control: .init(x: 60, y: 18)); p.addQuadCurve(to: .init(x: 51, y: 24.5), control: .init(x: 58, y: 24.5)); p.closeSubpath() } }
    static var lowerBeak: Path { Path { p in p.move(to: .init(x: 51, y: 24.5)); p.addQuadCurve(to: .init(x: 60.5, y: 26), control: .init(x: 57, y: 24.5)); p.addQuadCurve(to: .init(x: 51, y: 28), control: .init(x: 56, y: 28.5)); p.closeSubpath() } }
}
