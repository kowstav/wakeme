import AVFAudio
import SwiftUI

/// A call a friend can send as a quack: its sound, the word it makes and the face that shows it.
/// Same ids and order as Android and `ANIMAL_SOUNDS` in packages/policy; the duck's quack is first
/// and is the default.
struct AnimalSound: Identifiable, Hashable, Sendable {
    let id: String
    let name: String
    let face: WMFace
}

enum AnimalSounds {
    static let all: [AnimalSound] = [
        .init(id: "bundled:quack.classic", name: "Quack", face: .duck),
        .init(id: "bundled:frog.ribbit", name: "Ribbit", face: .frog),
        .init(id: "bundled:dog.woof", name: "Woof", face: .dog),
        .init(id: "bundled:cat.meow", name: "Meow", face: .cat),
        .init(id: "bundled:cow.moo", name: "Moo", face: .cow),
        .init(id: "bundled:monkey.hoot", name: "Ooh-ooh", face: .monkey),
        .init(id: "bundled:donkey.heehaw", name: "Hee-haw", face: .donkey),
        .init(id: "bundled:pig.oink", name: "Oink", face: .pig),
        .init(id: "bundled:sheep.baa", name: "Baa", face: .sheep),
        .init(id: "bundled:owl.hoot", name: "Hoot", face: .owl),
        .init(id: "bundled:rooster.crow", name: "Cock-a-doodle", face: .rooster),
    ]
    static let ids = all.map(\.id)
    static let defaultId = "bundled:quack.classic"

    static func sound(_ id: String) -> AnimalSound? { all.first { $0.id == id } }
}

/// Plays a bundled sound on this phone only, at media volume. A preview never sends anything.
@MainActor
final class SoundPreview {
    static let shared = SoundPreview()
    private var player: AVAudioPlayer?

    func play(_ soundId: String = AnimalSounds.defaultId) {
        let file = soundId.replacingOccurrences(of: "bundled:", with: "").replacingOccurrences(of: ".", with: "_")
        guard let url = Bundle.main.url(forResource: file, withExtension: "wav") else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
        player = try? AVAudioPlayer(contentsOf: url)
        player?.play()
    }
}

/// One animal in a sound row: its face, ringed in lime when chosen, and its word under it.
struct SoundChoice: View {
    let sound: AnimalSound
    let selected: Bool

    var body: some View {
        VStack(spacing: WMSpacing.xxs) {
            sound.face.image
                .resizable()
                .scaledToFit()
                .frame(width: 52, height: 52)
                .padding(3)
                .overlay(Circle().strokeBorder(selected ? WMColor.accent : .clear, lineWidth: 3))
            Text(sound.name)
                .font(WMFont.actionLabel.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? WMColor.textPrimary : WMColor.textSecondary)
                .lineLimit(1)
        }
        .frame(width: 68)
        .contentShape(Rectangle())
    }
}

/// The animals a friend allowed, side by side: tapping one picks it and plays it here (so you hear
/// what they will), and the send button takes its word.
struct AnimalSoundRow: View {
    let allowed: [String]
    @Binding var selection: String

    var body: some View {
        let sounds = AnimalSounds.all.filter { allowed.contains($0.id) }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: WMSpacing.xxs) {
                ForEach(sounds) { s in
                    Button {
                        selection = s.id
                        SoundPreview.shared.play(s.id)
                    } label: {
                        SoundChoice(sound: s, selected: s.id == selection)
                    }
                    .buttonStyle(WMPressStyle())
                    .accessibilityLabel(s.name)
                    .accessibilityAddTraits(s.id == selection ? .isSelected : [])
                }
            }
        }
        .sensoryFeedback(.selection, trigger: selection)
    }
}

/// The animals a friend may send you: each face toggles, all on by default; at least one stays.
struct AnimalSoundGrid: View {
    @Binding var sounds: [String]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 68), spacing: WMSpacing.xxs)], spacing: WMSpacing.sm) {
            ForEach(AnimalSounds.all) { s in
                let on = sounds.contains(s.id)
                Button {
                    if on { if sounds.count > 1 { sounds.removeAll { $0 == s.id } } } else { sounds.append(s.id) }
                    if !on { SoundPreview.shared.play(s.id) }
                } label: {
                    SoundChoice(sound: s, selected: on).opacity(on ? 1 : 0.45)
                }
                .buttonStyle(WMPressStyle())
                .accessibilityLabel(s.name)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}
