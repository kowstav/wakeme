import SwiftUI
import WakeMeCore

/// Runs a declarative challenge with native controls and checks every answer on this phone
/// (no network). A wrong answer is a calm retry, never a penalty; the simple check-in can
/// replace any step; and the escape is always on screen, never behind the challenge. Same
/// behaviour as Android's ChallengeRunner.
struct ChallengeRunnerView: View {
    let definition: ChallengeDefinition
    let escapeLabel: String
    let onComplete: (_ usedFallback: Bool) -> Void
    let onEscape: () -> Void

    @State private var run: ChallengeProgress
    @State private var checkIn = false
    /// Bumped on each answer: haptics follow them (a light tap for a wrong answer, a tick for a right one).
    @State private var wrongTick = 0
    @State private var rightTick = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(definition: ChallengeDefinition, escapeLabel: String, onComplete: @escaping (_ usedFallback: Bool) -> Void, onEscape: @escaping () -> Void) {
        self.definition = definition
        self.escapeLabel = escapeLabel
        self.onComplete = onComplete
        self.onEscape = onEscape
        _run = State(initialValue: ChallengeProgress(definition))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: WMSpacing.md) {
                Text(definition.title).font(.title2.bold()).foregroundStyle(WMColor.textPrimary).accessibilityAddTraits(.isHeader)
                Text("Step \(run.stepNumber) of \(definition.steps.count)").foregroundStyle(WMColor.textSecondary)
                StepProgress(done: run.finished ? definition.steps.count : run.index, total: definition.steps.count)
                if checkIn {
                    CheckInStep(prompt: definition.fallback.prompt) {
                        run.completeWithFallback()
                        onComplete(true)
                    }
                } else if let step = run.step {
                    // Each step arrives from the reading direction and fades in; input works at once.
                    StepView(step: step) { response in
                        if run.answer(response) {
                            rightTick += 1
                            if run.finished { onComplete(false) }
                        } else {
                            wrongTick += 1
                        }
                    }
                    .id(run.index) // fresh controls for each step
                    .transition(reduceMotion ? .opacity : .asymmetric(insertion: .offset(x: 24).combined(with: .opacity), removal: .opacity))
                    if run.retry {
                        // Calm, not punishing: no shake, no red. Spoken by VoiceOver.
                        StatusPill(text: "Not quite. Take your time and try again.", tone: .info, announce: true)
                            .transition(.opacity)
                    }
                }
            }
            .padding(WMSpacing.lg).frame(maxWidth: 640).frame(maxWidth: .infinity)
            .animation(WMMotion.respecting(reduceMotion, WMMotion.smooth), value: run.index)
            .animation(WMMotion.standard, value: run.retry)
        }
        .background(WMColor.surface)
        // The escape and the simple check-in stay on screen however long the challenge is or how
        // large the text gets (ADR 0015; same as Android). They are never animated.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: WMSpacing.xs) {
                if !checkIn, run.step != nil {
                    Button("Use the simple check-in instead") { checkIn = true }
                        .frame(minHeight: WMSize.minTouchTarget)
                }
                SecondaryButton(title: escapeLabel, action: onEscape)
                Text("This always works. Stopping is never locked behind the challenge.")
                    .font(.footnote).foregroundStyle(WMColor.textSecondary)
            }
            .padding(.horizontal, WMSpacing.lg).padding(.vertical, WMSpacing.sm)
            .frame(maxWidth: 640).frame(maxWidth: .infinity)
            .background(WMColor.surface)
            .overlay(alignment: .top) { Rectangle().fill(WMColor.border).frame(height: 1) }
        }
        .sensoryFeedback(WMHaptic.wrongAnswer, trigger: wrongTick)
        .sensoryFeedback(WMHaptic.selection, trigger: rightTick)
    }
}

/// One segment per step; finished steps fill with success. Visual only: "Step 2 of 3" is read.
private struct StepProgress: View {
    let done: Int
    let total: Int

    var body: some View {
        if total > 1 {
            HStack(spacing: WMSpacing.xxs) {
                ForEach(0..<total, id: \.self) { i in
                    Capsule().fill(i < done ? WMColor.success : WMColor.border).frame(height: 6)
                }
            }
            .animation(WMMotion.standard, value: done)
            .accessibilityHidden(true)
        }
    }
}

/// One step's controls; reports the person's answer for local verification.
private struct StepView: View {
    let step: ChallengeStep
    let onAnswer: (StepResponse) -> Void

    var body: some View {
        switch step {
        case let .multipleChoice(_, prompt, options, _):
            Prompt(prompt)
            ForEach(Array(options.enumerated()), id: \.offset) { i, option in
                SecondaryButton(title: option) { onAnswer(.index(i)) }
            }
        case let .shortAnswer(_, prompt, _):
            TextAnswer(prompt: prompt, keyboard: .default) { onAnswer(.text($0)) }
        case let .math(_, operands, op, _):
            TextAnswer(prompt: operands.map(String.init).joined(separator: " \(symbol(op)) ") + " = ?", keyboard: .numbersAndPunctuation) { onAnswer(.text($0)) }
        case let .memoryOrder(_, prompt, items, seconds):
            MemoryOrderStep(prompt: prompt, items: items, seconds: seconds) { onAnswer(.order($0)) }
        case let .chess(_, prompt, fen, _, _):
            ChessStep(prompt: prompt, fen: fen) { onAnswer(.move($0)) }
        case let .timer(_, prompt, seconds, _):
            TimerStep(prompt: prompt, seconds: seconds) { onAnswer(.elapsed($0)) }
        case let .qrCode(_, prompt, _):
            // Typed code only for now: scanning needs camera access, which this app doesn't request yet.
            TextAnswer(prompt: prompt + "\nType the letters printed under the code.", keyboard: .asciiCapable) { onAnswer(.text($0)) }
        case let .selfAttested(_, prompt, items, _, alternative):
            Checklist(prompt: prompt, items: items, alternative: alternative) { onAnswer(.checked($0)) }
        }
    }

    private func symbol(_ op: String) -> String {
        switch op {
        case "*": "×"
        case "/": "÷"
        case "-": "−"
        default: op
        }
    }
}

private struct Prompt: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.title3).foregroundStyle(WMColor.textPrimary) }
}

private struct TextAnswer: View {
    let prompt: String
    let keyboard: UIKeyboardType
    let onSubmit: (String) -> Void
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.md) {
            Prompt(prompt)
            TextField("Your answer", text: $text)
                .keyboardType(keyboard).textInputAutocapitalization(.never).autocorrectionDisabled()
                .font(.title2)
                .padding(WMSpacing.sm)
                .background(WMColor.surfaceRaised, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                .overlay(RoundedRectangle(cornerRadius: WMRadius.sm).stroke(WMColor.outline))
                .onChange(of: text) { _, v in if v.count > 80 { text = String(v.prefix(80)) } }
                .onSubmit { if !text.trimmingCharacters(in: .whitespaces).isEmpty { onSubmit(text) } }
            PrimaryButton(title: "Check") { onSubmit(text) }
                .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
}

private struct MemoryOrderStep: View {
    let prompt: String
    let items: [String]
    let seconds: Int
    let onSubmit: ([String]) -> Void
    @State private var showing = true
    @State private var picked: [String] = []
    @State private var shuffled: [String] = []

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.md) {
            Prompt(prompt)
            if showing {
                Text(items.joined(separator: "  →  ")).font(.title2).foregroundStyle(WMColor.textPrimary)
                Button("I've got it") { showing = false }.frame(minHeight: WMSize.minTouchTarget)
            } else {
                Text(picked.isEmpty ? "Tap them in the same order." : picked.joined(separator: " → ")).foregroundStyle(WMColor.textSecondary)
                ForEach(shuffled.filter { !picked.contains($0) }, id: \.self) { item in
                    SecondaryButton(title: item) {
                        picked.append(item)
                        if picked.count == items.count {
                            onSubmit(picked)
                            picked = []
                        }
                    }
                }
                if !picked.isEmpty { Button("Start over") { picked = [] }.frame(minHeight: WMSize.minTouchTarget) }
                Button("Show the list again") { picked = []; showing = true }.frame(minHeight: WMSize.minTouchTarget)
            }
        }
        .task {
            if shuffled.isEmpty { shuffled = items.shuffled() }
            try? await Task.sleep(for: .seconds(seconds))
            showing = false
        }
    }
}

/// 8×8 board from the FEN; tap a piece, then a square. A typed move is the accessible alternative.
private struct ChessStep: View {
    let prompt: String
    let fen: String
    let onMove: (String) -> Void
    @State private var from: String?
    @State private var typed = ""

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.md) {
            let board = parseFenBoard(fen)
            let whiteToMove = fen.split(separator: " ").dropFirst().first != "b"
            let ranks = whiteToMove ? Array((1...8).reversed()) : Array(1...8)
            let files = whiteToMove ? Array("abcdefgh") : Array("hgfedcba")
            Prompt(prompt)
            VStack(spacing: 0) {
                ForEach(ranks, id: \.self) { rank in
                    HStack(spacing: 0) {
                        ForEach(files, id: \.self) { file in
                            let square = "\(file)\(rank)"
                            let piece = board[square]
                            let dark = (Int(file.asciiValue! - 97) + rank) % 2 == 1 // a1 is dark
                            Button {
                                if let f = from {
                                    onMove(chessMove(from: f, to: square, board: board))
                                    from = nil
                                } else if piece != nil {
                                    from = square
                                }
                            } label: {
                                Text(piece.map(glyph) ?? " ")
                                    .font(.system(size: 28))
                                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                                    .background(from == square ? WMColor.accent : dark ? WMColor.surfaceRaised.mix(with: WMColor.outline, by: 0.32) : WMColor.surfaceRaised)
                            }
                            .buttonStyle(.plain)
                            .aspectRatio(1, contentMode: .fit)
                            .accessibilityLabel("\(square) \(piece.map(pieceName) ?? "empty")")
                        }
                    }
                }
            }
            .overlay(Rectangle().stroke(WMColor.border))
            Text("Or type the move, like a1a8 (from square, to square).").foregroundStyle(WMColor.textSecondary)
            TextField("Move", text: $typed)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(WMSpacing.sm)
                .background(WMColor.surfaceRaised, in: RoundedRectangle(cornerRadius: WMRadius.sm))
                .overlay(RoundedRectangle(cornerRadius: WMRadius.sm).stroke(WMColor.outline))
                .onChange(of: typed) { _, v in if v.count > 5 { typed = String(v.prefix(5)) } }
            PrimaryButton(title: "Play move") { onMove(typed) }.disabled(typed.count < 4)
        }
    }

    private func glyph(_ p: Character) -> String {
        ["K": "♔", "Q": "♕", "R": "♖", "B": "♗", "N": "♘", "P": "♙", "k": "♚", "q": "♛", "r": "♜", "b": "♝", "n": "♞", "p": "♟"][p] ?? ""
    }

    private func pieceName(_ p: Character) -> String {
        let names: [Character: String] = ["k": "king", "q": "queen", "r": "rook", "b": "bishop", "n": "knight", "p": "pawn"]
        return (p.isUppercase ? "white " : "black ") + (names[Character(p.lowercased())] ?? "")
    }
}

/// Measured with the monotonic clock, so changing the time on the phone can't skip it.
private struct TimerStep: View {
    let prompt: String
    let seconds: Int
    let onDone: (Double) -> Void
    @State private var startedAt: ContinuousClock.Instant?
    @State private var elapsed = 0
    @ScaledMetric(relativeTo: .largeTitle) private var timerSize: CGFloat = 56

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.md) {
            Prompt(prompt)
            if let startedAt {
                let left = max(seconds - elapsed, 0)
                Text(String(format: "%d:%02d", left / 60, left % 60)).font(.system(size: timerSize, weight: .bold, design: .rounded)).monospacedDigit().foregroundStyle(WMColor.textPrimary)
                Text("This is a timer and a self-check. It can't tell what you did.").foregroundStyle(WMColor.textSecondary)
                    .task(id: startedAt) {
                        while !Task.isCancelled {
                            let e = ContinuousClock.now - startedAt
                            elapsed = Int(e.components.seconds)
                            if elapsed >= seconds {
                                onDone(Double(elapsed))
                                return
                            }
                            try? await Task.sleep(for: .milliseconds(500))
                        }
                    }
            } else {
                PrimaryButton(title: "Start \(seconds / 60) min \(seconds % 60) s") { startedAt = .now }
            }
        }
    }
}

private struct Checklist: View {
    let prompt: String
    let items: [String]
    let alternative: String?
    let onSubmit: ([Bool]) -> Void
    @State private var checked: [Bool] = []

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.md) {
            Prompt(prompt)
            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                Toggle(item, isOn: Binding(get: { checked.indices.contains(i) && checked[i] }, set: { v in
                    if checked.count != items.count { checked = Array(repeating: false, count: items.count) }
                    checked[i] = v
                }))
                .foregroundStyle(WMColor.textPrimary)
            }
            if let alternative { Text(alternative).foregroundStyle(WMColor.textSecondary) }
            PrimaryButton(title: "Done") { onSubmit(checked) }
                .disabled(checked.count != items.count || !checked.allSatisfy { $0 })
        }
    }
}

/// Low-demand alternative for any step: two taps a few seconds apart. Counts as a self-check.
private struct CheckInStep: View {
    let prompt: String?
    let onDone: () -> Void
    @State private var first = false
    @State private var ready = false

    var body: some View {
        VStack(alignment: .leading, spacing: WMSpacing.md) {
            Prompt(prompt ?? "Tap \"I'm awake\", then tap it again when it lights up.")
            if !first {
                PrimaryButton(title: "I'm awake") { first = true }
            } else {
                PrimaryButton(title: ready ? "I'm awake (again)" : "Just a moment…") { onDone() }
                    .disabled(!ready)
                    .task {
                        try? await Task.sleep(for: .seconds(5))
                        ready = true
                    }
            }
        }
    }
}
