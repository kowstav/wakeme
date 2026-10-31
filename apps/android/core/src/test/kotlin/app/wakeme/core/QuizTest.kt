package app.wakeme.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class QuizTest {
    private val questions = listOf(
        QuizQuestion("Which café is headquarters?", "Chaos curry", listOf("Blue door", "Bean there")),
        QuizQuestion("Who always forgets the keys?", "Sam"),
    )

    @Test
    fun editingReadsBackWhatWasWritten() {
        val def = quizDefinition("Morning", questions, random = Random(7))
        val back = quizQuestions(def)!!
        assertEquals(questions[1], back[1])
        assertEquals(questions[0].prompt, back[0].prompt)
        assertEquals(questions[0].answer, back[0].answer)
        assertEquals(questions[0].wrong.toSet(), back[0].wrong.toSet())
    }

    @Test
    fun anUnchangedShortAnswerKeepsItsOtherSpellings() {
        val original = listOf(ChallengeStep.ShortAnswer("q1", "Who?", listOf("Sam", "Samuel")))
        assertEquals(listOf("Sam", "Samuel"), (quizSteps(listOf(QuizQuestion("Who, again?", "Sam")), original)[0] as ChallengeStep.ShortAnswer).acceptedAnswers)
        assertEquals(listOf("Alex"), (quizSteps(listOf(QuizQuestion("Who?", "Alex")), original)[0] as ChallengeStep.ShortAnswer).acceptedAnswers)
    }

    @Test
    fun repeatedOptionsAreDropped() {
        val step = quizSteps(listOf(QuizQuestion("Where?", "Home", listOf("home", "Park", "park"))))[0] as ChallengeStep.MultipleChoice
        assertEquals(setOf("Home", "Park"), step.options.toSet())
        assertEquals("Home", step.options[step.correctIndex])
        assertEquals(ChallengeStep.ShortAnswer("q1", "Where?", listOf("Home")), quizSteps(listOf(QuizQuestion("Where?", "Home", listOf("HOME"))))[0])
    }

    @Test
    fun templatesAreNotEditedAsQuizzes() {
        val maths = quizDefinition("Maths", questions).copy(steps = listOf(ChallengeStep.MathStep("m1", listOf(2, 3), "+", 5)))
        assertNull(quizQuestions(maths))
        assertNull(quizQuestions(maths.copy(steps = emptyList())))
    }

    @Test
    fun encodesAsTheServerExpects() {
        val json = WakeMeJson.encodeToJsonElement(ChallengeDefinition.serializer(), quizDefinition("", questions, random = Random(1))).jsonObject
        assertEquals("Our morning quiz", json["title"]!!.jsonPrimitive.content)
        assertEquals("80", json["maxDurationSeconds"]!!.jsonPrimitive.content)
        val steps = json["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("multiple_choice", "short_answer"), steps.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(listOf("creator_provided", "creator_provided"), steps.map { it["source"]!!.jsonPrimitive.content })
        assertEquals(null, steps[1]["factId"])
        assertEquals("accessible_checkin", json["fallback"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
