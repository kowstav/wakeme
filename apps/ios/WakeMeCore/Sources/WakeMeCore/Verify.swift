import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

// Offline challenge verification. Mirrors packages/challenges verify.ts and is
// checked against fixtures/challenge-verification.json.

/// JS `\s`: Unicode White_Space plus U+FEFF.
private func isJSWhitespace(_ u: Unicode.Scalar) -> Bool { u.properties.isWhitespace || u.value == 0xFEFF }

private func isPunctuationOrSymbol(_ u: Unicode.Scalar) -> Bool {
    switch u.properties.generalCategory {
    case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation, .initialPunctuation, .finalPunctuation, .otherPunctuation,
         .mathSymbol, .currencySymbol, .modifierSymbol, .otherSymbol:
        return true
    default:
        return false
    }
}

private func isMark(_ u: Unicode.Scalar) -> Bool {
    switch u.properties.generalCategory {
    case .nonspacingMark, .spacingMark, .enclosingMark: return true
    default: return false
    }
}

/// NFKC, then NFD with marks removed, lower-cased, punctuation and symbols as spaces, whitespace collapsed.
public func normalizeAnswer(_ input: String) -> String {
    let decomposed = input.precomposedStringWithCompatibilityMapping.decomposedStringWithCanonicalMapping
    var out = String.UnicodeScalarView()
    var pendingSpace = false
    for u in decomposed.unicodeScalars where !isMark(u) {
        for l in String(u).lowercased().unicodeScalars {
            if isJSWhitespace(l) || isPunctuationOrSymbol(l) {
                pendingSpace = true
            } else {
                if pendingSpace && !out.isEmpty { out.append(" ") }
                pendingSpace = false
                out.append(l)
            }
        }
    }
    return String(out)
}

/// QR payloads look like "WAKEME:K7P2QX9M"; the same code can be typed as "k7p2-qx9m".
public func normalizeCode(_ input: String) -> String {
    var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.lowercased().hasPrefix("wakeme:") { s = String(s.dropFirst(7)) }
    return String(String.UnicodeScalarView(s.uppercased().unicodeScalars.filter { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }))
}

public func sha256Hex(_ text: String) -> String {
    SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
}

public func codeHash(_ code: String) -> String { sha256Hex(normalizeCode(code)) }

/// Left-to-right evaluation; nil for inexact division, division by zero or overflow.
public func evaluateMath(_ operands: [Int64], _ op: String) -> Int64? {
    guard var acc = operands.first else { return nil }
    for v in operands.dropFirst() {
        let r: (Int64, Bool)
        switch op {
        case "+": r = acc.addingReportingOverflow(v)
        case "-": r = acc.subtractingReportingOverflow(v)
        case "*": r = acc.multipliedReportingOverflow(by: v)
        case "/":
            if v == 0 || acc % v != 0 { return nil }
            r = acc.dividedReportingOverflow(by: v)
        default: return nil
        }
        if r.1 { return nil }
        acc = r.0
    }
    return acc
}

private func isStrictInteger(_ t: String) -> Bool {
    var s = Substring(t)
    if let f = s.first, f == "+" || f == "-" { s = s.dropFirst() }
    return (1...9).contains(s.count) && s.unicodeScalars.allSatisfy { ("0"..."9").contains($0) }
}

public func verifyStep(_ step: ChallengeStep, _ response: StepResponse) -> Bool {
    switch (step, response) {
    case let (.multipleChoice(_, _, _, correct), .index(i)):
        return i == correct
    case let (.shortAnswer(_, _, accepted), .text(t)):
        let given = normalizeAnswer(t)
        return !given.isEmpty && accepted.contains { normalizeAnswer($0) == given }
    case let (.math(_, _, _, answer), .text(t)):
        let trimmed = t.trimmingCharacters(in: .whitespacesAndNewlines)
        return isStrictInteger(trimmed) && Int64(trimmed.hasPrefix("+") ? String(trimmed.dropFirst()) : trimmed) == answer
    case let (.memoryOrder(_, _, items, _), .order(o)):
        return o == items
    case let (.chess(_, _, _, solutions, _), .move(m)):
        return solutions.contains(m.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    case let (.timer(_, _, seconds, _), .elapsed(e)):
        return e >= Double(seconds)
    case let (.qrCode(_, _, hash), .text(t)):
        return codeHash(t) == hash
    case let (.selfAttested(_, _, items, _, _), .checked(c)):
        return c.count == items.count && c.allSatisfy { $0 }
    default:
        return false
    }
}
