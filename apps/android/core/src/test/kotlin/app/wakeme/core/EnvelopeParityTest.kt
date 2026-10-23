package app.wakeme.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** packages/policy-fixtures/fixtures/signed-envelopes.json: the same vectors the server and iOS verify. */
class EnvelopeParityTest {
    private val dir = File(System.getProperty("wakeme.fixtures") ?: "../../../packages/policy-fixtures/fixtures")

    private fun code(e: SignatureException): String = when (e.message) {
        "unsupported algorithm" -> "unsupported_algorithm"
        "unknown signing key" -> "unknown_key"
        "digest mismatch" -> "digest_mismatch"
        "bad signature" -> "bad_signature"
        "revision mismatch" -> "revision_mismatch"
        else -> "other: ${e.message}"
    }

    @Test
    fun envelopes() {
        val fx = WakeMeJson.parseToJsonElement(File(dir, "signed-envelopes.json").readText()).jsonObject
        val keys = fx["trustedKeys"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val cases = fx["cases"]!!.jsonArray
        assertEquals(7, cases.size)
        for (c in cases) {
            val o = c.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val env = WakeMeJson.decodeFromJsonElement(SignedEnvelope.serializer(), o["envelope"]!!)
            val exp = o["expected"]!!.jsonObject
            try {
                val p = ChallengeEnvelopes.verify(env, keys)
                if (!exp["ok"]!!.jsonPrimitive.boolean) fail("$name: expected ${exp["error"]}")
                assertEquals(name, exp["revision"]!!.jsonPrimitive.int, p.revision)
                assertEquals(name, exp["steps"]!!.jsonPrimitive.int, p.definition.steps.size)
            } catch (e: SignatureException) {
                assertEquals(name, exp["error"]?.jsonPrimitive?.content, code(e))
            }
        }
    }
}
