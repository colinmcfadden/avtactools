package app.ezpztac.planning

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A phase line's name, labels and ends (the web's feature/phaseLines/phaseLines.js), held to `planning/phaseLines.json`. */
class PhaseLinesFixtureTest {
    private val fixture = Fixtures.load("planning/phaseLines.json")
    private fun cases(key: String) = fixture.getValue(key).jsonArray.map { it.jsonObject }
    private fun name(c: JsonObject) = c.getValue("name").jsonPrimitive.content
    private fun pixels(e: JsonElement?): List<Pixel> = e!!.jsonArray.map { Pixel(it.jsonObject.getValue("x").jsonPrimitive.double, it.jsonObject.getValue("y").jsonPrimitive.double) }

    @Test
    fun `a name is cleaned as the web cleans it`() {
        for (c in cases("clean")) assertEquals(c.getValue("clean").jsonPrimitive.content, PhaseLines.cleanName(c["text"]), name(c))
    }

    @Test
    fun `a line is labelled PL and its name`() {
        for (c in cases("labels")) assertEquals(c.getValue("label").jsonPrimitive.content, PhaseLines.label(c["line"]), name(c))
    }

    @Test
    fun `the name offered is the first no line has`() {
        for (c in cases("next")) assertEquals(c.getValue("next").jsonPrimitive.content, PhaseLines.nextName(c["lines"] as? JsonArray), name(c))
    }

    @Test
    fun `a rename keeps everything else in its place, and a blank one changes nothing`() {
        for (c in cases("renames")) {
            val renamed = PhaseLines.rename(c.getValue("line").jsonObject, c.getValue("text").jsonPrimitive.content)
            assertEquals(c.getValue("renamed").jsonObject, renamed, name(c))
            assertEquals(c.getValue("renamed").jsonObject.keys.toList(), renamed.keys.toList(), name(c))
        }
    }

    @Test
    fun `a new line keeps places only`() {
        for (c in cases("creates")) {
            val points = c.getValue("points").jsonArray.map { LatLon(it.jsonObject.getValue("lat").jsonPrimitive.double, it.jsonObject.getValue("lon").jsonPrimitive.double) }
            val line = PhaseLines.create(points, c.getValue("id").jsonPrimitive.content, c.getValue("text").jsonPrimitive.content)
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("line"), line, 1e-12), name(c))
            assertEquals(c.getValue("line").jsonObject.keys.toList(), line.keys.toList(), name(c))
        }
    }

    @Test
    fun `each end points out of the line`() {
        for (c in cases("ends")) {
            val actual = JsonArray(PhaseLines.ends(pixels(c["pixels"])).map {
                JsonObject(mapOf("x" to JsonPrimitive(it.x), "y" to JsonPrimitive(it.y), "ux" to JsonPrimitive(it.ux), "uy" to JsonPrimitive(it.uy)))
            })
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("ends"), actual, 1e-8), name(c))
        }
    }

    @Test
    fun `a label is shifted so the end is on its edge`() {
        for (c in cases("shifts")) {
            val out = c.getValue("out").jsonObject
            val (fx, fy) = PhaseLines.labelShift(out.getValue("ux").jsonPrimitive.double, out.getValue("uy").jsonPrimitive.double)
            val shift = c.getValue("shift").jsonObject
            assertEquals(shift.getValue("fx").jsonPrimitive.double, fx, 1e-9, name(c))
            assertEquals(shift.getValue("fy").jsonPrimitive.double, fy, 1e-9, name(c))
        }
    }

    @Test
    fun `lines are the entries that are objects with an id`() {
        for (c in cases("data")) {
            assertEquals(emptyList<String>(), JsonCompare.differences(c.getValue("lines"), JsonArray(SetLines.fromData(c["data"], SetLines.PHASE_LINES)), 1e-12), name(c))
        }
    }
}
