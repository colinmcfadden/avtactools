package app.ezpztac.model

import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a saved set of points keeps through a read and a write: everything the web wrote, and what this version does not know. */
class PointSetTest {
    /** The points the web saved, as the real server returned them. */
    private val recorded: JsonArray = Fixtures.load("network/responses.json").getValue("responses").jsonArray
        .map { it.jsonObject }.single { it["name"]?.let { n -> (n as JsonPrimitive).content } == "pointset: get" }
        .getValue("body").jsonObject.getValue("points").jsonArray

    private fun doc(points: List<JsonElement>) = JsonObject(mapOf("points" to JsonArray(points)))

    private fun read(points: List<JsonElement>) = PointSets.parse("uuid-1", null, "SET", doc(points))

    private fun assertSameJson(expected: JsonElement, actual: JsonElement, what: String) {
        val differences = JsonCompare.differences(expected, actual)
        assertTrue(differences.isEmpty(), "$what: ${differences.take(8)}")
    }

    @Test
    fun `the points the web saved are read and written back as they were`() {
        val set = read(recorded)
        assertEquals(2, set.points.size)
        assertEquals("BLUE 1", set.points[0].name)
        assertEquals(1730.0, set.points[0].elevationFt)
        assertNull(set.points[1].elevationFt)
        assertEquals("lps-1-ef34gh", set.points[1].id)
        assertEquals(LatLon(34.601, -84.119), set.points[1].at)
        assertTrue(set.unreadable.isEmpty())
        assertSameJson(doc(recorded), PointSets.serialize(set), "the recorded set")
    }

    @Test
    fun `a field a newer release adds to a point survives an edit of the point`() {
        val newer = recorded.map { JsonObject(it.jsonObject + ("aFieldFromANewerRelease" to buildJsonObject { put("n", 1) })) }
        val set = read(newer)
        assertSameJson(doc(newer), PointSets.serialize(set), "a newer document")

        val moved = set.copy(points = set.points.map { if (it.id == "lps-0-ab12cd") it.copy(lat = 12.5) else it })
        val written = PointSets.serialize(moved).getValue("points").jsonArray.map { it.jsonObject }
        assertEquals(JsonPrimitive(12.5), written[0]["lat"])
        assertEquals(buildJsonObject { put("n", 1) }, written[0]["aFieldFromANewerRelease"])
    }

    @Test
    fun `a field this version knows is never overwritten by a stale extra`() {
        val stale = SetPoint("a", "NEW", "", "", "", null, 1.0, 2.0, extras = JsonObject(mapOf("name" to JsonPrimitive("OLD"), "other" to JsonPrimitive(1))))
        val written = PointSets.serialize(PointSet("u", null, "S", listOf(stale))).getValue("points").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("NEW"), written["name"])
        assertEquals(JsonPrimitive(1), written["other"])
    }

    @Test
    fun `an entry with no position is kept as it was, after the others, and counted`() {
        val noLat = buildJsonObject { put("id", "x"); put("name", "NO LAT"); put("lon", -84.0) }
        val aString = buildJsonObject { put("id", "y"); put("name", "STRING"); put("lat", "34.5"); put("lon", "-84.0") }       // a number written as text is not a position
        val notAnObject = JsonPrimitive("junk")
        val set = read(listOf(noLat, recorded[0], aString, notAnObject))

        assertEquals(1, set.points.size)
        assertEquals(listOf<JsonElement>(noLat, aString, notAnObject), set.unreadable)
        assertEquals(4, set.pointCount)
        val written = PointSets.serialize(set).getValue("points").jsonArray
        assertEquals(4, written.size)
        assertEquals(noLat, written[1])
        assertEquals(notAnObject, written[3])
    }

    @Test
    fun `text that is missing or not text is empty, an elevation that is not a number is none, and a point with no id is named by its place`() {
        val odd = buildJsonObject { put("name", 7); put("lat", 1.0); put("lon", 2.0); put("elevationFt", "high"); put("group", JsonNull) }
        val set = read(listOf(recorded[0], odd))
        val p = set.points[1]
        assertEquals("pt-1", p.id)
        assertEquals("", p.name)
        assertEquals("", p.group)
        assertEquals("", p.description)
        assertNull(p.elevationFt)
    }

    @Test
    fun `a document with no points is an empty set, and a set with none writes an empty list`() {
        assertEquals(0, PointSets.parse("u", null, "S", JsonObject(emptyMap())).points.size)
        assertEquals(0, PointSets.parse("u", null, "S", JsonObject(mapOf("points" to JsonNull))).points.size)
        assertEquals(JsonArray(emptyList()), PointSets.serialize(PointSet("u", null, "S", emptyList())).getValue("points"))
    }

    @Test
    fun `the saved id and the name belong to the record, not the document`() {
        val set = PointSets.parse("uuid-9", 42, "NAME", doc(recorded))
        assertEquals("uuid-9", set.id)
        assertEquals(42, set.savedId)
        assertEquals("NAME", set.name)
        assertEquals(setOf("points"), PointSets.serialize(set).keys)
    }

    @Test
    fun `a set made from an lps import takes the file's points in order, with an id for each from its place`() {
        val parsed = LocalPointSet(
            "NORTH GA",
            listOf(
                LocalPoint("BLUE 1", "Landing zone", "LZ", "lz", 1730.0, 34.5123, -84.2231),
                LocalPoint("FARP", "", "Default", "", null, 34.601, -84.119),
            ),
        )
        val set = PointSets.fromLps("uuid-1", parsed) { "lps-$it" }
        assertEquals("NORTH GA", set.name)
        assertEquals(listOf("lps-0", "lps-1"), set.points.map { it.id })
        assertEquals("LZ", set.points[0].group)
        assertNull(set.points[1].elevationFt)
        // and it reads back as itself
        assertEquals(set, PointSets.parse("uuid-1", null, "NORTH GA", PointSets.serialize(set)))
    }

    @Test
    fun `a point is found by its id`() {
        val set = read(recorded)
        assertEquals("FARP", set.point("lps-1-ef34gh")!!.name)
        assertNull(set.point("nope"))
    }
}
