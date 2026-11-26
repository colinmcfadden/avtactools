package app.ezpztac.planning

import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The FLOT's scallops, label and flip (the web's feature/flot/flots.js), held to `planning/flots.json`. */
class FlotsFixtureTest {
    private val fixture = Fixtures.load("planning/flots.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }

    private fun pixels(e: JsonElement?): List<Pixel> = e!!.jsonArray.map { Pixel(it.jsonObject.getValue("x").jsonPrimitive.double, it.jsonObject.getValue("y").jsonPrimitive.double) }
    private fun json(p: Pixel) = JsonObject(mapOf("x" to JsonPrimitive(p.x), "y" to JsonPrimitive(p.y)))

    @Test
    fun `scallops follow the line in whole scallops, and a box leaves out what is off it`() {
        for (c in cases("scallops")) {
            val name = c.getValue("name").jsonPrimitive.content
            val within = (c["within"] as? JsonObject)?.let {
                PixelBox(it.getValue("minX").jsonPrimitive.double, it.getValue("minY").jsonPrimitive.double, it.getValue("maxX").jsonPrimitive.double, it.getValue("maxY").jsonPrimitive.double)
            }
            val runs = Flots.scallopRuns(
                pixels(c["pixels"]),
                diameter = (c["diameter"] as? JsonPrimitive)?.double ?: Flots.SCALLOP_PX,
                flipped = (c["flipped"] as? JsonPrimitive)?.boolean ?: false,
                within = within,
            )
            val actual = JsonArray(runs.map { run -> JsonArray(run.map(::json)) })
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("runs"), actual, 1e-5), name)
        }
    }

    @Test
    fun `the label is at the middle by distance, away from the scallops`() {
        for (c in cases("labels")) {
            val name = c.getValue("name").jsonPrimitive.content
            val spot = Flots.labelSpot(pixels(c["pixels"]), (c["flipped"] as? JsonPrimitive)?.boolean ?: false)
            val actual = spot?.let {
                JsonObject(mapOf("x" to JsonPrimitive(it.x), "y" to JsonPrimitive(it.y), "awayX" to JsonPrimitive(it.awayX), "awayY" to JsonPrimitive(it.awayY)))
            } ?: JsonNull
            assertEquals(emptyList<String>(), JsonCompare.differences(c["spot"] ?: JsonNull, actual, 1e-8), name)
        }
    }

    @Test
    fun `a flip puts flipped last, and takes it away again`() {
        for (c in cases("flips")) {
            val name = c.getValue("name").jsonPrimitive.content
            val flipped = Flots.flip(c.getValue("flot").jsonObject)
            assertEquals(c.getValue("flipped").jsonObject, flipped, name)
            assertEquals(c.getValue("flipped").jsonObject.keys.toList(), flipped.keys.toList(), name)
        }
    }
}
