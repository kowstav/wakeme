package app.wakeme.core

import kotlin.random.Random

/** One question as the quiz editor shows it: no wrong options means a short answer. */
data class QuizQuestion(val prompt: String, val answer: String, val wrong: List<String> = emptyList())

/**
 * The questions of a quiz-shaped challenge (only multiple-choice and short-answer steps),
 * for editing; null for anything else (templates such as maths or chess are not edited here).
 */
fun quizQuestions(def: ChallengeDefinition): List<QuizQuestion>? {
    if (def.steps.isEmpty()) return null
    return def.steps.map { s ->
        when (s) {
            is ChallengeStep.MultipleChoice -> QuizQuestion(s.prompt, s.options.getOrNull(s.correctIndex) ?: return null, s.options.filterIndexed { i, _ -> i != s.correctIndex })
            is ChallengeStep.ShortAnswer -> QuizQuestion(s.prompt, s.acceptedAnswers.firstOrNull() ?: return null, emptyList())
            else -> return null
        }
    }
}

/**
 * Steps for a quiz. When editing, [original] is the revision being edited: a short answer whose
 * main answer is unchanged keeps its other accepted spellings.
 */
fun quizSteps(questions: List<QuizQuestion>, original: List<ChallengeStep> = emptyList(), random: Random = Random.Default): List<ChallengeStep> =
    questions.mapIndexed { i, q ->
        val id = "q${i + 1}"
        // Options must differ from each other and from the answer (the server checks).
        val seen = mutableSetOf(q.answer.lowercase())
        val wrong = q.wrong.filter { seen.add(it.lowercase()) }.take(5)
        if (wrong.isEmpty()) {
            val before = original.getOrNull(i) as? ChallengeStep.ShortAnswer
            val accepted = if (before != null && before.acceptedAnswers.firstOrNull() == q.answer) before.acceptedAnswers else listOf(q.answer)
            ChallengeStep.ShortAnswer(id, q.prompt, accepted)
        } else {
            val options = (wrong + q.answer).shuffled(random)
            ChallengeStep.MultipleChoice(id, q.prompt, options, options.indexOf(q.answer))
        }
    }

/** A quiz definition, the same shape the server validates for quizzes written on a phone. */
fun quizDefinition(title: String, questions: List<QuizQuestion>, difficulty: String = "easy", original: List<ChallengeStep> = emptyList(), random: Random = Random.Default) = ChallengeDefinition(
    schemaVersion = 1,
    title = title.ifBlank { "Our morning quiz" },
    kind = "sequence",
    difficulty = difficulty,
    maxDurationSeconds = (questions.size * 25 + 30).coerceAtMost(600),
    steps = quizSteps(questions, original, random),
    verification = "local_deterministic",
    fallback = Fallback("accessible_checkin"),
    permissionsRequired = emptyList(),
)
