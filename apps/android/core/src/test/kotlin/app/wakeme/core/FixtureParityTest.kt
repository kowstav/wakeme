package app.wakeme.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Runs the server's golden fixtures (packages/policy-fixtures) against the
 * Kotlin implementation, so server and Android make identical decisions.
 */
class FixtureParityTest {
    private val dir = File(System.getProperty("wakeme.fixtures") ?: "../../../packages/policy-fixtures/fixtures")
    private fun load(name: String): JsonObject = WakeMeJson.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject

    private fun merge(base: JsonElement, override: JsonElement?): JsonElement {
        if (override == null) return base
        if (override !is JsonObject || base !is JsonObject) return override
        val out = base.toMutableMap()
        for ((k, v) in override) out[k] = if (v is JsonNull) JsonNull else merge(base[k] ?: JsonNull, v)
        return JsonObject(out)
    }

    @Test
    fun timeWindows() {
        val fx = load("time-windows")
        for (c in fx["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val window = WakeMeJson.decodeFromJsonElement<LocalWindow>(o["window"]!!)
            val got = isWithinWindow(Instant.parse(o["instant"]!!.jsonPrimitive.content), ZoneId.of(o["timeZone"]!!.jsonPrimitive.content), window)
            assertEquals(o["name"]!!.jsonPrimitive.content, o["expected"]!!.jsonPrimitive.boolean, got)
        }
        for (c in fx["quietCases"]!!.jsonArray) {
            val o = c.jsonObject
            val quiet = WakeMeJson.decodeFromJsonElement<QuietHours>(o["quiet"]!!)
            val got = isQuietTime(Instant.parse(o["instant"]!!.jsonPrimitive.content), ZoneId.of(o["timeZone"]!!.jsonPrimitive.content), quiet)
            assertEquals(o["name"]!!.jsonPrimitive.content, o["expected"]!!.jsonPrimitive.boolean, got)
        }
    }

    @Test
    fun occurrences() {
        for (c in load("occurrences")["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val rule = o["rule"]!!.jsonObject
            val got = nextOccurrences(
                rule["localTime"]!!.jsonPrimitive.content,
                ZoneId.of(rule["timeZone"]!!.jsonPrimitive.content),
                WakeMeJson.decodeFromJsonElement<Recurrence>(rule["recurrence"]!!),
                Instant.parse(o["after"]!!.jsonPrimitive.content),
                o["count"]!!.jsonPrimitive.int,
            ).map { it.localDate to it.instant.toString() }
            val expected = o["expected"]!!.jsonArray.map { it.jsonObject["localDate"]!!.jsonPrimitive.content to it.jsonObject["instant"]!!.jsonPrimitive.content }
            assertEquals(o["name"]!!.jsonPrimitive.content, expected, got)
        }
    }

    @Test
    fun localExecution() {
        val fx = load("local-execution")
        for (c in fx["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val m = merge(fx["base"]!!, o["override"]).jsonObject
            val cmd = m["command"]!!.jsonObject
            val grant = m["localGrant"]!!.let { if (it is JsonNull) null else WakeMeJson.decodeFromJsonElement<GrantSnapshot>(it) }
            val decision = evaluateLocalExecution(
                now = Instant.parse(m["now"]!!.jsonPrimitive.content),
                command = IncomingCommand(
                    id = cmd["id"]!!.jsonPrimitive.content,
                    capability = cmd["capability"]!!.jsonPrimitive.content,
                    actorId = cmd["actorId"]!!.jsonPrimitive.content,
                    grantVersion = cmd["grantVersion"]!!.jsonPrimitive.int,
                    expiresAt = cmd["expiresAt"]!!.jsonPrimitive.content,
                    soundId = cmd["soundId"]?.jsonPrimitive?.contentOrNull,
                    ringSeconds = cmd["ringSeconds"]?.jsonPrimitive?.int,
                ),
                localGrant = grant,
                zone = ZoneId.of(m["timeZone"]!!.jsonPrimitive.content),
                settings = WakeMeJson.decodeFromJsonElement(m["settings"]!!),
                alreadyProcessed = m["alreadyProcessed"]!!.jsonPrimitive.boolean,
            )
            val exp = o["expected"]!!.jsonObject
            val expected = if (exp["execute"]!!.jsonPrimitive.boolean) LocalDecision.Execute(exp["mode"]!!.jsonPrimitive.content) else LocalDecision.Skip(exp["reason"]!!.jsonPrimitive.content)
            assertEquals(o["name"]!!.jsonPrimitive.content, expected, decision)
        }
    }

    @Test
    fun deliveryState() {
        for (c in load("delivery-state")["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val events = o["events"]!!.jsonArray.map {
                val e = it.jsonObject
                DeliveryEvent(e["kind"]!!.jsonPrimitive.content, e["seq"]!!.jsonPrimitive.long, e["reason"]?.jsonPrimitive?.contentOrNull)
            }
            val state = reduceDelivery(events)
            val es = o["expected"]!!.jsonObject["state"]!!.jsonObject
            val expectedState = DeliveryState(
                es["lifecycle"]!!.jsonPrimitive.content,
                es["transport"]!!.jsonPrimitive.content,
                es["device"]!!.jsonPrimitive.content,
                es["human"]!!.jsonPrimitive.content,
                es["refusedReason"]!!.jsonPrimitive.contentOrNull,
            )
            val name = o["name"]!!.jsonPrimitive.content
            assertEquals(name, expectedState, state)
            val opts = o["options"]!!.jsonObject
            val sentAt = Instant.parse("2026-10-19T14:00:00Z")
            val now = sentAt.plusSeconds(opts["secondsSinceSent"]!!.jsonPrimitive.long)
            assertEquals(name, o["expected"]!!.jsonObject["display"]!!.jsonPrimitive.content, displayStatus(state, now, sentAt, opts["kind"]!!.jsonPrimitive.content, opts["shareBypass"]!!.jsonPrimitive.boolean))
        }
    }

    @Test
    fun challengeVerification() {
        val fx = load("challenge-verification")
        for (c in fx["normalizationCases"]!!.jsonArray) {
            val o = c.jsonObject
            assertEquals(o["input"]!!.jsonPrimitive.content, o["expected"]!!.jsonPrimitive.content, normalizeAnswer(o["input"]!!.jsonPrimitive.content))
        }
        for (c in fx["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val step = WakeMeJson.decodeFromJsonElement(ChallengeStep.serializer(), o["step"]!!)
            val r = o["response"]!!.jsonObject
            val response = when {
                "index" in r -> StepResponse.Index(r["index"]!!.jsonPrimitive.int)
                "text" in r -> StepResponse.Text(r["text"]!!.jsonPrimitive.content)
                "order" in r -> StepResponse.Order((r["order"] as JsonArray).map { it.jsonPrimitive.content })
                "move" in r -> StepResponse.Move(r["move"]!!.jsonPrimitive.content)
                "elapsedSeconds" in r -> StepResponse.Elapsed(r["elapsedSeconds"]!!.jsonPrimitive.content.toDouble())
                else -> StepResponse.Checked(r["checked"]!!.jsonArray.map { it.jsonPrimitive.boolean })
            }
            assertEquals(o["name"]!!.jsonPrimitive.content, o["expected"]!!.jsonPrimitive.boolean, verifyStep(step, response))
        }
    }
}
