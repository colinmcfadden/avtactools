package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagramGeometryTest {
    private fun pair(a: Any?, b: Any?) = JsonArray(listOf(prim(a), prim(b)))
    private fun prim(v: Any?) = when (v) { null -> JsonNull; is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }

    private fun diagram(detected: JsonArray? = null, custom: JsonArray? = null) = Diagram(
        id = "d", createdAt = "t", updatedAt = "t",
        analysis = DiagramAnalysis(detectedLZ = detected ?: JsonNull, customLZ = custom ?: JsonNull),
    )

    @Test
    fun `points are latitude then longitude`() {
        assertEquals(listOf(LatLon(34.5, -84.1)), DiagramGeometry.points(JsonArray(listOf(pair(34.5, -84.1)))))
    }

    @Test
    fun `what is not a pair of numbers is left out, not guessed at`() {
        val saved = JsonArray(listOf(pair(1, 2), JsonPrimitive("x"), JsonArray(listOf(JsonPrimitive(1))), pair("a", "b"), pair(null, 3), JsonNull, pair(5, 6)))
        assertEquals(listOf(LatLon(1.0, 2.0), LatLon(5.0, 6.0)), DiagramGeometry.points(saved))
    }

    @Test
    fun `anything that is not an array is no points`() {
        assertEquals(emptyList<LatLon>(), DiagramGeometry.points(null))
        assertEquals(emptyList<LatLon>(), DiagramGeometry.points(JsonNull))
        assertEquals(emptyList<LatLon>(), DiagramGeometry.points(JsonPrimitive("nope")))
    }

    @Test
    fun `numeric text is read as the number it spells, as the web's map would, but NaN and infinity are not positions`() {
        assertEquals(listOf(LatLon(34.5, -84.1)), DiagramGeometry.points(JsonArray(listOf(pair("34.5", "-84.1")))))
        assertEquals(emptyList<LatLon>(), DiagramGeometry.points(JsonArray(listOf(pair("NaN", 1), pair("Infinity", 1), pair(1, "-Infinity")))))
    }

    @Test
    fun `a boundary needs three points, a drawn one two`() {
        val two = JsonArray(listOf(pair(1, 2), pair(3, 4)))
        val three = JsonArray(listOf(pair(1, 2), pair(3, 4), pair(5, 6)))
        assertEquals(emptyList<LatLon>(), DiagramGeometry.boundary(diagram(detected = two)))
        assertEquals(3, DiagramGeometry.boundary(diagram(detected = three)).size)
        assertEquals(2, DiagramGeometry.drawn(diagram(custom = two)).size)
        assertEquals(emptyList<LatLon>(), DiagramGeometry.drawn(diagram(custom = JsonArray(listOf(pair(1, 2))))))
    }

    @Test
    fun `a boundary of three entries one of which is rubbish is only two points, so no boundary`() {
        val saved = JsonArray(listOf(pair(1, 2), pair(3, 4), JsonPrimitive("junk")))
        assertEquals(emptyList<LatLon>(), DiagramGeometry.polygon(saved))
    }
}
