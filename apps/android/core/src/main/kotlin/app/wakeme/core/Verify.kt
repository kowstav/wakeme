package app.wakeme.core

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

// Offline challenge verification. Mirrors packages/challenges verify.ts and is
// checked against fixtures/challenge-verification.json.

private val MARKS = Regex("\\p{M}+")
private val PUNCT_SYMBOL = Regex("[\\p{P}\\p{S}]")
// The same characters as JavaScript's /\s/u, listed explicitly: Android's ICU regex engine
// rejects the inline Unicode-classes flag the JVM accepts, which crashed every quiz answer
// check on a phone (NormalizeAnswerTest keeps it out).
private val WHITESPACE = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
private val INTEGER = Regex("^[+-]?\\d{1,9}$")

fun normalizeAnswer(input: String): String {
    val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
    val stripped = Normalizer.normalize(nfkc, Normalizer.Form.NFD).replace(MARKS, "")
    return stripped.lowercase(Locale.ROOT).replace(PUNCT_SYMBOL, " ").replace(WHITESPACE, " ").trim()
}

fun normalizeCode(input: String): String =
    input.trim().replace(Regex("^(?i)wakeme:"), "").uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun codeHash(code: String): String = sha256Hex(normalizeCode(code))

/** Left-to-right evaluation; null for inexact division or division by zero. */
fun evaluateMath(operands: List<Long>, operator: String): Long? {
    if (operands.isEmpty()) return null
    var acc = operands[0]
    for (v in operands.drop(1)) {
        acc = when (operator) {
            "+" -> acc + v
            "-" -> acc - v
            "*" -> acc * v
            "/" -> if (v == 0L || acc % v != 0L) return null else acc / v
            else -> return null
        }
    }
    return acc
}

fun verifyStep(step: ChallengeStep, response: StepResponse): Boolean = when (step) {
    is ChallengeStep.MultipleChoice -> response is StepResponse.Index && response.index == step.correctIndex
    is ChallengeStep.ShortAnswer -> response is StepResponse.Text && normalizeAnswer(response.text).let { given ->
        given.isNotEmpty() && step.acceptedAnswers.any { normalizeAnswer(it) == given }
    }
    is ChallengeStep.MathStep -> response is StepResponse.Text && response.text.trim().let { t ->
        INTEGER.matches(t) && t.toLong() == step.answer
    }
    is ChallengeStep.MemoryOrder -> response is StepResponse.Order && response.order == step.items
    is ChallengeStep.Chess -> response is StepResponse.Move && response.move.trim().lowercase(Locale.ROOT) in step.solutions
    is ChallengeStep.Timer -> response is StepResponse.Elapsed && response.elapsedSeconds >= step.seconds
    is ChallengeStep.QrCode -> response is StepResponse.Text && codeHash(response.text) == step.codeHash
    is ChallengeStep.SelfAttested -> response is StepResponse.Checked && response.checked.size == step.items.size && response.checked.all { it }
}
