import SwiftUI

/// Where a time sits in the sky. Daytime runs 06:00 to 18:00 and night the other twelve hours, so
/// `fraction` goes 0 to 1 across one arc and a 06:15 alarm is a sun only just over the horizon.
/// The same arithmetic as Android's `skyPosition`.
struct SkyPosition: Equatable {
    let fraction: CGFloat
    let daytime: Bool

    init(localTime: String) {
        let parts = localTime.split(separator: ":")
        let hour = parts.first.flatMap { Int($0) } ?? 7
        let minute = parts.count > 1 ? Int(parts[1]) ?? 0 : 0
        let minutes = min(max(hour * 60 + minute, 0), 1439)
        daytime = minutes >= 360 && minutes < 1080
        fraction = CGFloat(daytime ? minutes - 360 : (minutes + 360) % 1440) / 720
    }
}

/// The half ellipse the sun travels, left horizon to right horizon.
private func skyPath(center: CGPoint, reachX: CGFloat, reachY: CGFloat) -> Path {
    var path = Path()
    for step in 0...48 {
        let angle = CGFloat.pi * CGFloat(step) / 48
        let point = CGPoint(x: center.x - reachX * cos(angle), y: center.y - reachY * sin(angle))
        if step == 0 { path.move(to: point) } else { path.addLine(to: point) }
    }
    return path
}

/// The signature: a thin horizon and a dotted path with the sun (a crescent moon at night) sitting
/// exactly where this time falls in the day. It replaces a repeated sun icon with something each
/// alarm owns. Decorative: the time beside it is what VoiceOver reads. Never animates. At
/// accessibility text sizes it steps aside, so the time keeps the width it needs.
struct DawnArc: View {
    let localTime: String
    var width: CGFloat = WMLayout.dawnArcWidth
    var height: CGFloat = WMLayout.dawnArcHeight
    /// The surface behind the arc: the moon's crescent is cut with it.
    var sky: Color = WMColor.heroFill
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        if !typeSize.isAccessibilitySize { arc }
    }

    private var arc: some View {
        let position = SkyPosition(localTime: localTime)
        return Canvas { context, size in
            let horizon = size.height - 1
            let radius = size.height * 0.15
            let center = CGPoint(x: size.width / 2, y: horizon)
            let reachX = size.width / 2 - radius
            let reachY = horizon - radius - 1

            let path = skyPath(center: center, reachX: reachX, reachY: reachY)
            context.stroke(path, with: .color(WMColor.onHeroLine), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [0.1, 5]))

            var ground = Path()
            ground.move(to: CGPoint(x: 0, y: horizon))
            ground.addLine(to: CGPoint(x: size.width, y: horizon))
            context.stroke(ground, with: .color(WMColor.onHeroLine), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))

            let angle = CGFloat.pi * (1 - position.fraction)
            let spot = CGPoint(x: center.x + reachX * cos(angle), y: horizon - reachY * sin(angle))
            let disc = CGRect(x: spot.x - radius, y: spot.y - radius, width: radius * 2, height: radius * 2)
            // The horizon hides what hasn't risen yet.
            context.clip(to: Path(CGRect(x: 0, y: 0, width: size.width, height: horizon)))
            if position.daytime {
                context.fill(Path(ellipseIn: disc), with: .color(WMColor.sun))
            } else {
                context.fill(Path(ellipseIn: disc), with: .color(WMColor.moon))
                let bite = disc.offsetBy(dx: radius * 0.55, dy: -radius * 0.2)
                context.fill(Path(ellipseIn: bite), with: .color(sky))
            }
        }
        .frame(width: width, height: height)
        .accessibilityHidden(true)
    }
}

/// The welcome's scene: the same sun, half risen behind lavender hills. Drawn in code so it fills
/// any window and follows the theme; the sky is left empty for the headline. Decorative.
struct DawnScene: View {
    var body: some View {
        Canvas { context, size in
            let w = size.width
            let h = size.height
            let radius = min(w, h) * 0.3
            let sun = CGPoint(x: w * 0.68, y: h * 0.56)

            let path = skyPath(center: CGPoint(x: w * 0.5, y: h * 0.74), reachX: w * 0.62, reachY: h * 0.62)
            context.stroke(path, with: .color(WMColor.hillFar), style: StrokeStyle(lineWidth: 2, lineCap: .round, dash: [0.1, 9]))

            context.fill(Path(ellipseIn: CGRect(x: sun.x - radius, y: sun.y - radius, width: radius * 2, height: radius * 2)), with: .color(WMColor.sun))
            context.fill(hill(w, h, start: 0.60, c1: (0.22, 0.42), c2: (0.40, 0.46), mid: (0.56, 0.60), c3: (0.72, 0.74), c4: (0.86, 0.56), end: 0.52), with: .color(WMColor.hillFar))
            context.fill(hill(w, h, start: 0.70, c1: (0.20, 0.82), c2: (0.38, 0.86), mid: (0.54, 0.76), c3: (0.68, 0.66), c4: (0.84, 0.66), end: 0.76), with: .color(WMColor.hillNear))
            context.fill(hill(w, h, start: 0.92, c1: (0.24, 0.78), c2: (0.46, 0.84), mid: (0.62, 0.92), c3: (0.76, 0.98), c4: (0.90, 0.92), end: 0.84), with: .color(WMColor.hillFront))
        }
        .accessibilityHidden(true)
    }

    private func hill(_ w: CGFloat, _ h: CGFloat, start: CGFloat, c1: (CGFloat, CGFloat), c2: (CGFloat, CGFloat), mid: (CGFloat, CGFloat), c3: (CGFloat, CGFloat), c4: (CGFloat, CGFloat), end: CGFloat) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: 0, y: h))
        p.addLine(to: CGPoint(x: 0, y: h * start))
        p.addCurve(to: CGPoint(x: w * mid.0, y: h * mid.1), control1: CGPoint(x: w * c1.0, y: h * c1.1), control2: CGPoint(x: w * c2.0, y: h * c2.1))
        p.addCurve(to: CGPoint(x: w, y: h * end), control1: CGPoint(x: w * c3.0, y: h * c3.1), control2: CGPoint(x: w * c4.0, y: h * c4.1))
        p.addLine(to: CGPoint(x: w, y: h))
        p.closeSubpath()
        return p
    }
}
