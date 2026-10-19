import Foundation

/// One run through a challenge on the phone, verified locally with `verifyStep` (no
/// network). A wrong answer is a calm retry, never a penalty; the low-demand check-in
/// (the definition's fallback) can replace any step. Same behaviour as Android's runner.
public struct ChallengeProgress: Equatable, Sendable {
    public let definition: ChallengeDefinition
    public private(set) var index = 0
    public private(set) var usedFallback = false
    public private(set) var finished = false
    /// The last answer was wrong; cleared by the next answer.
    public private(set) var retry = false

    public init(_ definition: ChallengeDefinition) {
        self.definition = definition
        finished = definition.steps.isEmpty
    }

    public var step: ChallengeStep? { finished || index >= definition.steps.count ? nil : definition.steps[index] }
    public var stepNumber: Int { min(index + 1, definition.steps.count) }

    /// Checks the answer for the current step; advances (or finishes) when it's right.
    @discardableResult
    public mutating func answer(_ response: StepResponse) -> Bool {
        guard let step else { return false }
        guard verifyStep(step, response) else {
            retry = true
            return false
        }
        retry = false
        index += 1
        if index >= definition.steps.count { finished = true }
        return true
    }

    /// Completes the challenge through the accessible check-in (a self-check, not verification).
    public mutating func completeWithFallback() {
        usedFallback = true
        retry = false
        finished = true
    }
}

/// Squares ("e4") to FEN piece letters for the piece-placement field of a FEN string.
public func parseFenBoard(_ fen: String) -> [String: Character] {
    var out: [String: Character] = [:]
    let placement = fen.split(separator: " ").first.map(String.init) ?? ""
    for (r, row) in placement.split(separator: "/").enumerated() where r < 8 {
        var file = 0
        for ch in row {
            if let n = ch.wholeNumberValue {
                file += n
            } else if file < 8 {
                out["\(Character(UnicodeScalar(UInt8(97 + file))))\(8 - r)"] = ch
                file += 1
            }
        }
    }
    return out
}

/// The move string a chess step expects ("e7e8q"): from-square, to-square, and queen
/// promotion when a pawn reaches the last rank.
public func chessMove(from: String, to: String, board: [String: Character]) -> String {
    let isPawn = board[from].map { $0 == "P" || $0 == "p" } ?? false
    let lastRank = to.hasSuffix("8") || to.hasSuffix("1")
    return from + to + (isPawn && lastRank ? "q" : "")
}
