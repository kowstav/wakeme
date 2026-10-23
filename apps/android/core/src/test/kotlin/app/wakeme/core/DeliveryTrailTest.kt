package app.wakeme.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** packages/policy-fixtures/fixtures/delivery-trail.json: the same table iOS checks. */
class DeliveryTrailTest {
    private val dir = File(System.getProperty("wakeme.fixtures") ?: "../../../packages/policy-fixtures/fixtures")

    @Test
    fun fixtureCases() {
        val fx = WakeMeJson.parseToJsonElement(File(dir, "delivery-trail.json").readText()).jsonObject
        val cases = fx["cases"]!!.jsonArray
        assertTrue(cases.size > 20)
        for (c in cases.map { it.jsonObject }) {
            val kind = c["kind"]!!.jsonPrimitive.content
            val display = c["display"]!!.jsonPrimitive.content
            val expected = c["expected"]!!.jsonObject
            val trail = DeliveryTrail.of(display, alarm = kind == "alarm")
            assertEquals("$kind $display reached", expected["reached"]!!.jsonPrimitive.int, trail.reached)
            assertEquals("$kind $display state", expected["state"]!!.jsonPrimitive.content.uppercase(), trail.state.name)
        }
    }

    @Test
    fun neverClaimsAwake() {
        // The furthest any status reaches is the third step ("rang"), never beyond.
        for (s in listOf("challenge_completed", "acknowledged", "stopped")) {
            assertTrue(DeliveryTrail.of(s, alarm = true).reached <= DeliveryTrail.STEPS)
        }
    }
}
