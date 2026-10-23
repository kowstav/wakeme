import SwiftUI
import WakeMeCore

/// True inside a `WMCard` or tile: quiet controls (secondary buttons, icon buttons) sit on white
/// there by day, and on the page's neutral grey everywhere else, so they never vanish into the
/// card behind them.
private struct OnCardKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var wmOnCard: Bool {
        get { self[OnCardKey.self] }
        set { self[OnCardKey.self] = newValue }
    }
}

/// The fill of a quiet control: card grey on the page, white on a card by day, a lighter tone by night.
private func quietFill(onCard: Bool, scheme: ColorScheme) -> Color {
    guard onCard else { return WMColor.surfaceNeutral }
    return scheme == .dark ? WMColor.surfacePressed : WMColor.surfaceRaised
}

/// `fill` while pressed: 8 % of the text colour over it, so every surface darkens (or lightens at
/// night) the same way.
private func pressed(_ fill: Color) -> Color { fill.mix(with: WMColor.textPrimary, by: 0.08) }

/// A Bloom icon (packages/design-tokens/icons) in the current foreground style, scaled with
/// Dynamic Type. Always decorative: the words beside it, or the control's label, carry the meaning.
struct WMIconImage: View {
    let icon: WMIcon
    let size: CGFloat
    @ScaledMetric(relativeTo: .body) private var unit: CGFloat = 1

    init(_ icon: WMIcon, size: CGFloat = WMLayout.iconSize) {
        self.icon = icon
        self.size = size
    }

    var body: some View {
        icon.image
            .resizable()
            .renderingMode(.template)
            .scaledToFit()
            .frame(width: size * min(unit, 1.5), height: size * min(unit, 1.5))
            .accessibilityHidden(true)
    }
}

/// An icon on a petal-coloured circle: rows, settings and tiles use these to tell things apart at a glance.
struct IconChip: View {
    let icon: WMIcon
    let family: WMFamily
    var size: CGFloat = WMLayout.iconChip

    var body: some View {
        WMIconImage(icon, size: size * 0.55)
            .foregroundStyle(family.ink)
            .frame(width: size, height: size)
            .background(family.fill, in: Circle())
            .accessibilityHidden(true)
    }
}

/// A button's label: an optional leading icon, then the words (which VoiceOver reads).
private struct ButtonLabel: View {
    let title: String
    let icon: WMIcon?

    var body: some View {
        HStack(spacing: WMSpacing.xs) {
            if let icon { WMIconImage(icon, size: WMLayout.iconSizeSmall) }
            Text(title).font(WMFont.cardTitle).multilineTextAlignment(.center)
        }
    }
}

/// One clear primary action per screen: the lime capsule with forest ink, 56 pt tall with a 17 pt
/// semibold label (both follow Dynamic Type). An `icon` leads the label where it adds recognition
/// (send, invite, add); the words stay. Pressing deepens the fill and settles the button slightly;
/// with Reduce Motion only the fill changes.
struct PrimaryButton: View {
    let title: String
    var icon: WMIcon? = nil
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.wmOnCard) private var onCard
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            ButtonLabel(title: title, icon: icon)
                .foregroundStyle(isEnabled ? WMColor.textOnAccent : WMColor.textPrimary.opacity(WMOpacity.disabled))
                .padding(.horizontal, WMLayout.buttonPaddingX)
                .padding(.vertical, WMSpacing.xs)
                .frame(maxWidth: .infinity, minHeight: WMLayout.buttonHeight)
        }
        // Disabled on a card, the card's grey would hide the button's shape (Focus, ios run 45):
        // the quiet fill keeps it visible there.
        .buttonStyle(WMPressStyle(fill: isEnabled ? WMColor.accent : quietFill(onCard: onCard, scheme: scheme), pressedFill: WMColor.accentPressed))
    }
}

/// A filled neutral capsule (the reference apps' "Sign in with Apple" button): grey on the page,
/// white on a card. Same height and label as `PrimaryButton`, so paired actions line up and the
/// lime fill alone says which one leads. Its words identify it, so it needs no outline.
struct SecondaryButton: View {
    let title: String
    var icon: WMIcon? = nil
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.wmOnCard) private var onCard
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let fill = quietFill(onCard: onCard, scheme: scheme)
        Button(action: action) {
            ButtonLabel(title: title, icon: icon)
                .foregroundStyle(WMColor.textPrimary)
                .padding(.horizontal, WMLayout.buttonPaddingX)
                .padding(.vertical, WMSpacing.xs)
                .frame(maxWidth: .infinity, minHeight: WMLayout.buttonHeight)
        }
        .buttonStyle(WMPressStyle(fill: fill, pressedFill: pressed(fill)))
        .opacity(isEnabled ? 1 : 0.45)
    }
}

/// Destructive actions only (end, delete, leave). Never animated: it must look and act the same
/// every time.
struct DangerButton: View {
    let title: String
    var icon: WMIcon? = nil
    let action: () -> Void

    var body: some View {
        Button(role: .destructive, action: action) {
            ButtonLabel(title: title, icon: icon)
                .foregroundStyle(WMColor.textOnDanger)
                .padding(.horizontal, WMLayout.buttonPaddingX)
                .padding(.vertical, WMSpacing.xs)
                .frame(maxWidth: .infinity, minHeight: WMLayout.buttonHeight)
                .background(WMColor.dangerFill, in: Capsule())
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// A compact pill (40 pt visual in a 44 pt target) for an inline action: an icon and a short label,
/// sized to its words (the reference apps' "Invite" and "Play" pills). `primary` makes it lime.
struct PillButton: View {
    let title: String
    var icon: WMIcon? = nil
    var primary = false
    let action: () -> Void
    @Environment(\.wmOnCard) private var onCard
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let fill = primary ? WMColor.accent : quietFill(onCard: onCard, scheme: scheme)
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon { WMIconImage(icon, size: 18) }
                Text(title).font(WMFont.label)
            }
            .foregroundStyle(primary ? WMColor.textOnAccent : WMColor.textPrimary)
            .padding(.horizontal, WMSpacing.md)
            .frame(minHeight: WMLayout.compactControlHeight)
        }
        .buttonStyle(WMPressStyle(fill: fill, pressedFill: primary ? WMColor.accentPressed : pressed(fill)))
        .frame(minHeight: WMSize.minTouchTarget)
    }
}

/// An icon-only control for actions everyone recognises (play a preview, more): a 44 pt circle.
/// `label` is required: it is what VoiceOver says.
struct RoundIconButton: View {
    let icon: WMIcon
    let label: String
    let action: () -> Void
    @Environment(\.wmOnCard) private var onCard
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let fill = quietFill(onCard: onCard, scheme: scheme)
        Button(action: action) {
            WMIconImage(icon, size: 22)
                .foregroundStyle(WMColor.textPrimary)
                .frame(width: WMLayout.iconButtonVisual, height: WMLayout.iconButtonVisual)
        }
        .buttonStyle(WMPressStyle(fill: fill, pressedFill: pressed(fill), shape: AnyShape(Circle())))
        .accessibilityLabel(label)
    }
}

/// The look of a quick action (the reference apps' row of round buttons): a 56 pt circle with an
/// icon and one short word under it. Wrap it in a `Button` or `NavigationLink`, or use `QuickAction`.
struct QuickActionLabel: View {
    let icon: WMIcon
    let title: String
    var family: WMFamily? = nil
    var primary = false
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.wmOnCard) private var onCard
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        // A family colour at half strength: the row reads as a set of soft petals, so the circles
        // never outshout the face and name above them.
        let petal: Color? = family.map { (f: WMFamily) -> Color in f.fill.opacity(0.5) }
        let fill: Color = primary ? WMColor.accent : petal ?? quietFill(onCard: onCard, scheme: scheme)
        let ink = primary ? WMColor.textOnAccent : family?.ink ?? WMColor.textPrimary
        VStack(spacing: WMSpacing.xs) {
            WMIconImage(icon)
                .foregroundStyle(ink)
                .frame(width: WMLayout.actionCircle, height: WMLayout.actionCircle)
                .background(fill, in: Circle())
            Text(title)
                .font(WMFont.actionLabel)
                .foregroundStyle(WMColor.textPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity)
        .opacity(isEnabled ? 1 : WMOpacity.disabled)
        .contentShape(Rectangle())
    }
}

/// A quick action as a button. `spokenLabel` lets the one visible word stand for a longer
/// spoken action ("Suggest" → "Suggest a wake-up routine").
struct QuickAction: View {
    let icon: WMIcon
    let title: String
    var family: WMFamily? = nil
    var primary = false
    var spokenLabel: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) { QuickActionLabel(icon: icon, title: title, family: family, primary: primary) }
            .buttonStyle(WMPressStyle())
            .accessibilityLabel(spokenLabel ?? title)
    }
}

/// The inside of a bento tile: its icon top-left and its title at the bottom-left, on the family's
/// fill (the reference apps' account and category tiles). Tiles of one kind take different
/// families, like petals of one flower. Wrap it with `.buttonStyle(.tile(family))`, or use `BentoTile`.
struct BentoTileLabel: View {
    let title: String
    let icon: WMIcon
    let family: WMFamily
    var supporting: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.xxs) {
            WMIconImage(icon)
                .foregroundStyle(family.ink)
                .frame(width: WMLayout.iconChip, height: WMLayout.iconChip)
                .background(family.ink.opacity(0.12), in: Circle())
            Spacer(minLength: WMSpacing.sm)
            Text(title).font(WMFont.tileTitle).foregroundStyle(family.ink).multilineTextAlignment(.leading)
            if let supporting { Text(supporting).font(WMFont.supporting).foregroundStyle(family.ink).multilineTextAlignment(.leading) }
        }
        .padding(WMLayout.cardPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .frame(minHeight: WMLayout.tileMinHeight)
    }
}

/// Two (or three) bento tiles side by side, each the height of the tallest.
struct BentoRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: WMLayout.cardGap) { content }
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A bento tile as one button, read as its title (the supporting line is the hint).
struct BentoTile: View {
    let title: String
    let icon: WMIcon
    let family: WMFamily
    var supporting: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) { BentoTileLabel(title: title, icon: icon, family: family, supporting: supporting) }
            .buttonStyle(.tile(family))
            .accessibilityLabel(title)
            .accessibilityHint(supporting ?? "")
    }
}

extension ButtonStyle where Self == WMPressStyle {
    /// A tile's press: its own fill, a touch of its ink while pressed, the tile's corners.
    static func tile(_ family: WMFamily) -> WMPressStyle {
        WMPressStyle(fill: family.fill, pressedFill: family.fill.mix(with: family.ink, by: 0.1), shape: AnyShape(RoundedRectangle(cornerRadius: WMRadius.lg, style: .continuous)))
    }
}

/// The forest hero card (Bloom's one dark-green surface): the next alarm, a routine's time. Text
/// on it uses `onHero` (lime) and `onHeroSecondary`.
struct HeroCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.xs) { content }
            .padding(WMLayout.heroPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WMColor.heroFill, in: RoundedRectangle(cornerRadius: WMRadius.lg, style: .continuous))
    }
}

/// The hero headline: SF Pro Condensed Black in capitals (tokens: type.roles.display), in forest by
/// day and lime by night. It grows with Dynamic Type up to 1.3×; VoiceOver reads the sentence as
/// written, not letter by letter.
struct DisplayText: View {
    let text: String
    let alignment: TextAlignment
    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 46

    init(_ text: String, alignment: TextAlignment = .leading) {
        self.text = text
        self.alignment = alignment
    }

    var body: some View {
        Text(text)
            .textCase(.uppercase)
            .font(.system(size: min(size, 46 * 1.3), weight: .black).width(.condensed))
            .foregroundStyle(WMColor.brandInk)
            .multilineTextAlignment(alignment)
            .minimumScaleFactor(0.7)
            .accessibilityLabel(text)
            .accessibilityAddTraits(.isHeader)
    }
}

extension View {
    /// Rows of a grouped list on Bloom's white page: each group takes the card grey, and quiet
    /// controls inside switch to white so they stay visible. Apply to a `Group` of sections.
    func cardRows() -> some View {
        listRowBackground(WMColor.surfaceNeutral)
            .environment(\.wmOnCard, true)
    }

    /// A row of a grouped list that draws its own surface (a hero, a bento row): no row background,
    /// no separator, no side insets.
    func bentoRow() -> some View {
        listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: WMSpacing.xs, leading: 0, bottom: WMSpacing.xs, trailing: 0))
    }
}

/// A flat card: neutral grey on the white page by day, a lighter tonal surface by night; no border,
/// no shadow (every reviewed app separates cards by fill alone).
struct WMCard<Content: View>: View {
    var fill: Color = WMColor.surfaceNeutral
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.sm) { content }
            .environment(\.wmOnCard, true)
            .padding(WMLayout.cardPadding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: WMRadius.lg, style: .continuous))
    }
}

enum Tone { case neutral, info, success, warning, danger }

/// Status is always words plus a dot (or an icon), never colour alone. The tinted pill makes the
/// tone visible at a glance; a change crossfades (no movement). With `announce`, VoiceOver speaks
/// changes, for statuses that change while the person waits (delivery, readiness).
struct StatusPill: View {
    let text: String
    var tone: Tone = .neutral
    var announce = false
    var icon: WMIcon? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let icon {
                WMIconImage(icon, size: 16).foregroundStyle(dot)
            } else {
                Circle().fill(dot).frame(width: 8, height: 8).accessibilityHidden(true)
            }
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
        case .neutral: WMColor.textPrimary.opacity(0.08)
        case .info: WMColor.infoContainer
        case .success: WMColor.successContainer
        case .warning: WMColor.warningContainer
        case .danger: WMColor.dangerContainer
        }
    }
}

/// The icon a status pill leads with, so its tone is a shape as well as a colour.
func toneIcon(_ tone: Tone) -> WMIcon? {
    switch tone {
    case .success: return WMIcon.checkCircle
    case .warning, .danger: return WMIcon.warning
    case .info: return WMIcon.info
    case .neutral: return nil
    }
}

/// A friend's face: one of ten cartoon animals, chosen from a stable key so the same friend always
/// has the same animal (on both platforms: same hash, same order). Decorative: the name beside it is
/// what VoiceOver reads.
struct FriendAvatar: View {
    let name: String
    let key: String
    var size: CGFloat = WMLayout.avatarRow

    var body: some View {
        faceFor(key).image
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// The animal for a stable key (a friend's or circle's id). Java's String.hashCode and floorMod, so
/// it matches Android (Swift's hashValue is randomised per launch).
func faceFor(_ key: String) -> WMFace {
    let faces = WMFace.avatars
    let hash = key.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
    return faces[((Int(hash) % faces.count) + faces.count) % faces.count]
}

/// The petal family for a stable key (a friend's or circle's id). Java's String.hashCode and
/// floorMod, so it matches Android (Swift's hashValue is randomised per launch).
func familyFor(_ key: String, offset: Int = 0) -> WMFamily {
    let families = WMPalette.avatars
    let hash = key.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
    let shifted = Int(hash) + offset
    return families[((shifted % families.count) + families.count) % families.count]
}

/// A routine's time of day as an icon in its own petal colour: mornings a gold sun, afternoons an
/// apricot sun, evenings and nights a periwinkle moon. Decorative; the time itself is the text.
func timeOfDay(_ localTime: String) -> (icon: WMIcon, family: WMFamily) {
    let hour = Int(localTime.prefix(while: { $0 != ":" })) ?? 7
    switch hour {
    case 4...11: return (.sun, WMPalette.gold)
    case 12...16: return (.sun, WMPalette.apricot)
    default: return (.moon, WMPalette.periwinkle)
    }
}

/// Sent → reached their phone → seen (quack) / rang (wake alarm), as three icon stops on a track
/// (the reference apps' order trackers). Steps fill only for evidence the server reported
/// (`DeliveryTrail`, shared fixture), and the fill animates only when that status changes, never on
/// a timer. Visual only: the literal status pill beside it is what VoiceOver reads and announces,
/// so nothing is said twice.
struct DeliveryTrailView: View {
    let displayStatus: String
    let alarm: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let trail = DeliveryTrail(displayStatus: displayStatus, alarm: alarm)
        let labels = ["Sent", "Reached their phone", alarm ? "Rang on their phone" : "They saw it"]
        let icons: [WMIcon] = [.send, .phone, alarm ? .wake : .eye]
        let node: CGFloat = 32
        VStack(spacing: WMSpacing.xs) {
            GeometryReader { geo in
                let r = node / 2
                let xs = [r, geo.size.width / 2, geo.size.width - r]
                let filled = trail.reached <= 1 ? xs[0] : xs[min(trail.reached, 3) - 1]
                ZStack(alignment: .leading) {
                    Capsule().fill(WMColor.border).frame(width: xs[2] - xs[0], height: 4).offset(x: xs[0])
                    Capsule().fill(WMColor.success).frame(width: max(0, filled - xs[0]), height: 4).offset(x: xs[0])
                    ForEach(0..<3, id: \.self) { i in
                        stop(i, icon: icons[i], trail).frame(width: node, height: node).position(x: xs[i], y: geo.size.height / 2)
                    }
                }
                .animation(WMMotion.respecting(reduceMotion, WMMotion.smooth), value: trail.reached)
            }
            .frame(height: node)
            // The pill above says the same thing in words; at accessibility sizes the step labels
            // would stack several lines deep, so the trail stays visual-only there.
            if !typeSize.isAccessibilitySize {
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
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder private func stop(_ i: Int, icon: WMIcon, _ trail: DeliveryTrail) -> some View {
        if i < trail.reached {
            WMIconImage(icon, size: 16).foregroundStyle(WMColor.textOnAccent).frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.accent, in: Circle())
        } else if i == trail.reached, trail.state == .problem {
            WMIconImage(.close, size: 16).foregroundStyle(WMColor.warning).frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.warningContainer, in: Circle())
        } else {
            WMIconImage(icon, size: 16).foregroundStyle(WMColor.textSecondary).frame(maxWidth: .infinity, maxHeight: .infinity).background(WMColor.surfaceNeutral, in: Circle())
        }
    }
}

/// How far through a short task you are, as segments that fill one by one (the goal-gradient: the
/// nearer the end looks, the more people finish). Visual; the screen's words carry the meaning.
struct StepProgress: View {
    let total: Int
    let done: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: WMSpacing.xxs) {
            ForEach(0..<total, id: \.self) { i in
                Capsule()
                    .fill(i < done ? WMColor.accent : WMColor.surfaceNeutral)
                    .frame(height: 6)
            }
        }
        .animation(WMMotion.respecting(reduceMotion, WMMotion.standard), value: done)
        .accessibilityElement()
        .accessibilityLabel("Step \(min(done + 1, total)) of \(total)")
    }
}

/// A caption under the control it qualifies, the way the reference apps write footnotes: a short
/// line of secondary text, no icon (an icon beside wrapped text never lines up with it). Most
/// screens need none: people who use apps already know what a switch or a button does.
struct Note: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(WMFont.supporting)
            .foregroundStyle(WMColor.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A calm empty state: a friendly face and what belongs here, centred on its title; a message
/// only where the title alone wouldn't do.
struct EmptyState: View {
    let title: String
    var message: String? = nil

    var body: some View {
        HStack(alignment: message == nil ? .center : .top, spacing: WMSpacing.sm) {
            faceFor(title).image.resizable().scaledToFit().frame(width: 52, height: 52).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: WMSpacing.xxs) {
                Text(title).font(WMFont.cardTitle).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                if let message { Text(message).foregroundStyle(WMColor.textSecondary) }
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
