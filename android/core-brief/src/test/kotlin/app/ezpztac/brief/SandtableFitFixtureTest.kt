package app.ezpztac.brief

import app.ezpztac.brief.SandtableSteps.Box
import app.ezpztac.brief.SandtableSteps.Insets
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * contracts/fixtures/brief/sandtable-fit.json's `insets`: what the web's `fitInsets` (sandtableSteps.js) keeps clear of the boxes laid over the map, case for case,
 * squeezes and ties included (the web's own tests hold those loosely). Its `views`, Leaflet's framing, hold feature-brief's SandtableCamera.
 */
class SandtableFitFixtureTest {
    private val cases = Fixtures.load("brief/sandtable-fit.json")["insets"]!!.jsonArray.map { it.jsonObject }

    private fun box(value: JsonElement?): Box? = (value as? JsonObject)?.let { o ->
        Box(o["left"]!!.jsonPrimitive.double, o["top"]!!.jsonPrimitive.double, o["right"]!!.jsonPrimitive.double, o["bottom"]!!.jsonPrimitive.double)
    }

    @TestFactory
    fun `every case of fitInsets`(): List<DynamicTest> = cases.map { c ->
        DynamicTest.dynamicTest(c["name"]!!.jsonPrimitive.content) {
            val options = c["options"] as? JsonObject
            val margin = options?.get("margin")?.jsonPrimitive?.double ?: 16.0
            val min = options?.get("min")?.jsonPrimitive?.double ?: 160.0
            val map = c["map"].takeIf { it != null && it !is JsonNull }?.let(::box)
            val insets = c["insets"]!!.jsonObject
            val expected = Insets(insets["top"]!!.jsonPrimitive.double, insets["right"]!!.jsonPrimitive.double, insets["bottom"]!!.jsonPrimitive.double, insets["left"]!!.jsonPrimitive.double)
            assertEquals(expected, SandtableSteps.fitInsets(map, c["covers"]!!.jsonArray.map(::box), margin, min))
        }
    }
}
