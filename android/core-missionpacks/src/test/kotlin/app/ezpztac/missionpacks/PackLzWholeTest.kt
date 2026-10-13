package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.assertValue
import app.ezpztac.missionpacks.PackFixtures.localId
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * A planning graphic's place and the target are each one value ([LzPackKind.keepWhole]): a change that moves one in one direction only (the nudge pad's
 * arrows change one coordinate) is sent with the rest of it, and someone else's is laid over an older version whole, so two people's moves never leave a
 * graphic at one's latitude and the other's longitude. A PZ marker's anchor and tip are one value. Android only, so held here.
 */
class PackLzWholeTest {
    private val kind = LzPackKind(PackFixtures.env())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // LZ HAWK with one of each graphic that has a place, a sector, and a boundary.
    private val hawk = PackItemView(
        "lz-1", "lz", "LZ HAWK",
        json(
            """
            {"schemaVersion": 2, "status": "analyzed",
             "target": {"lat": 34.5, "lon": -84.1, "mgrs": "16S GC 28864 55349"}, "mapData": {"mgrs": "16S GC 28864 55349"},
             "flightData": {"landing_hdg": "090°"},
             "analysis": {"customLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], "detectedLZ": [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]],
                          "results": {"area": 1}, "gridElevation": "1200", "latLong": ""},
             "graphics": {
               "helicopters": [{"id": 101, "lat": 34.5, "lon": -84.1, "rotation": 90, "type": "helo", "profileId": "uh60l"}],
               "pzMarkers": [{"id": "pz-1", "lat": 34.502, "lon": -84.102, "tipLat": 34.502, "tipLon": -84.104}],
               "sectorsOfFire": [{"id": "sec-1", "points": [{"lat": 34.503, "lng": -84.1}, {"lat": 34.501, "lng": -84.099}, {"lat": 34.501, "lng": -84.101}]}],
               "goArounds": [{"id": "ga-1", "lat": 34.499, "lon": -84.1, "direction": "left", "rotation": 0}],
               "doghouses": [{"id": "dh-rp", "role": "landing", "lat": 34.5, "lon": -84.103, "id_val": "[RP1]", "heading": "090°", "time": "02+10", "dist": "5.20km", "airspeed": "40 KIAS"}],
               "units": [{"id": "unit-1", "sidc": "SFGPUCI----D---", "uniqueDesignation": "A", "higherFormation": "1-327", "lat": 34.498, "lon": -84.098}],
               "measurements": [], "exportBox": null}}
            """,
        ),
    )

    private val local = localId(hawk)

    // The graphic of [collection] with [id] in [doc] changed by [patch], as the inspector and a drag change one (`DiagramOps.patchGraphic`).
    private fun patched(doc: Diagram, collection: String, id: JsonElement, patch: String): Diagram = DiagramOps.patchGraphic(doc, collection, id, json(patch))

    // What [mine], made from HAWK, is sent as.
    private fun sent(mine: Diagram): List<JsonObject> {
        val plan = checkNotNull(PackItemSync.flushPlan(kind, local, PackItemSync.settled(kind, local, hawk), hawk, mine, "Sam B.")) { "nothing was sent" }
        return plan.ops.map { JsonObject(it - "summary") }
    }

    private fun opened(): Diagram = kind.fromItem(local, hawk, null)

    // Graphic [id] of [collection] in item data [data].
    private fun graphic(data: JsonElement, collection: String, id: JsonElement): JsonObject =
        data.jsonObject.getValue("graphics").jsonObject.getValue(collection).jsonArray.single { it.jsonObject["id"] == id }.jsonObject

    // Graphic [id] of [collection] as [doc] has it, read through its shape.
    private fun graphic(doc: Diagram, collection: String, id: JsonElement): JsonObject = graphic(kind.docOf(doc, hawk.data), collection, id)

    private fun place(o: JsonObject, vararg fields: String): JsonObject = JsonObject(fields.associateWith { o.getValue(it) })

    @Test
    fun `a graphic moved one way only is sent with both its coordinates, for every graphic that is placed`() {
        for ((collection, id) in listOf("helicopters" to JsonPrimitive(101), "goArounds" to JsonPrimitive("ga-1"), "doghouses" to JsonPrimitive("dh-rp"), "units" to JsonPrimitive("unit-1"))) {
            val before = graphic(hawk.data, collection, id)
            // The nudge pad's arrow north: the latitude changes, the longitude is exactly what it was.
            val north = patched(opened(), collection, id, """{"lat": 34.5123, "lon": ${before.getValue("lon")}}""")
            val move = sent(north).single()
            assertValue(json("""{"lat": 34.5123, "lon": ${before.getValue("lon")}}"""), move["value"], "$collection: its place, whole")
            // East only: the latitude goes with it.
            val east = patched(opened(), collection, id, """{"lon": -84.0876}""")
            assertValue(json("""{"lon": -84.0876, "lat": ${before.getValue("lat")}}"""), sent(east).single()["value"], "$collection: east, whole")
        }
    }

    @Test
    fun `a PZ marker's anchor and tip are one value, so moved north or turned by its tip alone all four go`() {
        val id = JsonPrimitive("pz-1")
        // Moved north, as the nudge pad moves one: the anchor and the tip by the same latitude, their longitudes as they were.
        val north = patched(opened(), "pzMarkers", id, """{"lat": 34.503, "lon": -84.102, "tipLat": 34.503, "tipLon": -84.104}""")
        assertValue(json("""{"lat": 34.503, "tipLat": 34.503, "lon": -84.102, "tipLon": -84.104}"""), sent(north).single()["value"], "moved north")
        // Turned, or its reach changed, or its tip dragged: the tip alone changes, and the anchor goes with it.
        val turned = patched(opened(), "pzMarkers", id, """{"tipLat": 34.504, "tipLon": -84.102}""")
        assertValue(json("""{"tipLat": 34.504, "tipLon": -84.102, "lat": 34.502, "lon": -84.102}"""), sent(turned).single()["value"], "turned")
    }

    @Test
    fun `the target moved one way only is sent with its grid and the other coordinate`() {
        val moved = opened().copy(target = DiagramTarget(34.5011, -84.1, "16S GC 28864 55349"))
        val op = sent(moved).single()
        assertValue(JsonArray(listOf(JsonPrimitive("target"))), op["path"], "at the target")
        assertValue(json("""{"lat": 34.5011, "lon": -84.1, "mgrs": "16S GC 28864 55349"}"""), op["value"], "the target, whole")
    }

    @Test
    fun `what is not a place goes as it changed`() {
        val turned = patched(opened(), "helicopters", JsonPrimitive(101), """{"rotation": 135}""")
        assertValue(json("""{"rotation": 135}"""), sent(turned).single()["value"], "a turn")
        val labelled = patched(opened(), "doghouses", JsonPrimitive("dh-rp"), """{"id_val": "[RP2]"}""")
        assertValue(json("""{"id_val": "[RP2]"}"""), sent(labelled).single()["value"], "a label")
        val unit = patched(opened(), "units", JsonPrimitive("unit-1"), """{"uniqueDesignation": "B"}""")
        assertValue(json("""{"uniqueDesignation": "B"}"""), sent(unit).single()["value"], "a unit's label")
        val ops = listOf(json("""{"type": "patch", "path": ["graphics", "helicopters", {"id": 101}], "value": {"rotation": 135}}"""))
        assertSame(ops.single(), kind.keepWhole(ops, kind.docOf(turned, hawk.data)).single(), "the same operation")
    }

    @Test
    fun `a sector's corners and a boundary's go whole, as lists without ids`() {
        val sector = graphic(hawk.data, "sectorsOfFire", JsonPrimitive("sec-1"))
        val corners = sector.getValue("points").jsonArray
        val oneCorner = JsonArray(listOf(json("""{"lat": 34.5035, "lng": -84.1}""")) + corners.drop(1))
        val moved = patched(opened(), "sectorsOfFire", JsonPrimitive("sec-1"), """{"points": $oneCorner}""")
        assertValue(JsonObject(mapOf("points" to oneCorner)), sent(moved).single()["value"], "a sector's corners, whole")

        val drawn = opened().let { it.copy(analysis = it.analysis.copy(customLZ = Json.parseToJsonElement("[[34.5, -84.1], [34.512, -84.1], [34.51, -84.09]]"))) }
        val op = sent(drawn).single()
        assertValue(JsonArray(listOf(JsonPrimitive("analysis"))), op["path"], "at the analysis")
        assertValue(json("""{"customLZ": [[34.5, -84.1], [34.512, -84.1], [34.51, -84.09]]}"""), op["value"], "the boundary, whole")
    }

    @Test
    fun `a move one way only, sent after someone else put the graphic elsewhere, leaves it where this person put it`() {
        val elsewhere = PackFixtures.applyAll(
            "lz", hawk.data,
            listOf(json("""{"type": "patch", "path": ["graphics", "helicopters", {"id": 101}], "value": {"lat": 34.47, "lon": -84.13}}""")),
        )
        val north = patched(opened(), "helicopters", JsonPrimitive(101), """{"lat": 34.5123}""")
        val arrived = PackFixtures.applyAll("lz", elsewhere, sent(north))
        assertValue(json("""{"lat": 34.5123, "lon": -84.1}"""), place(graphic(arrived, "helicopters", JsonPrimitive(101)), "lat", "lon"), "where it is")
    }

    @Test
    fun `someone else's move of a graphic one way only is laid over an older version whole`() {
        val base = PackItemSync.settled(kind, local, hawk)
        val showing = opened()
        // An older version of this person's, from before they moved the aircraft (undo would bring it back).
        val older = patched(showing, "helicopters", JsonPrimitive(101), """{"lat": 34.49, "lon": -84.11}""")
        // Someone else moved it north only.
        val theirs = hawk.copy(
            data = PackFixtures.applyAll(
                "lz", hawk.data, listOf(json("""{"type": "patch", "path": ["graphics", "helicopters", {"id": 101}], "value": {"lat": 34.5077}}""")),
            ),
        )
        val taken = PackItemSync.takeTheirs(kind, local, base, theirs)
        val expected = json("""{"lat": 34.5077, "lon": -84.1}""")
        assertValue(expected, place(graphic(taken.apply(showing), "helicopters", JsonPrimitive(101)), "lat", "lon"), "the version showing")
        assertValue(expected, place(graphic(taken.apply(older), "helicopters", JsonPrimitive(101)), "lat", "lon"), "the older version: their place, not half of it")
        // And a PZ marker turned elsewhere, laid over a version where it was moved: their arrow, anchor and all.
        val olderPz = patched(showing, "pzMarkers", JsonPrimitive("pz-1"), """{"lat": 34.49, "lon": -84.11, "tipLat": 34.49, "tipLon": -84.112}""")
        val turned = hawk.copy(
            data = PackFixtures.applyAll(
                "lz", hawk.data, listOf(json("""{"type": "patch", "path": ["graphics", "pzMarkers", {"id": "pz-1"}], "value": {"tipLat": 34.504, "tipLon": -84.102}}""")),
            ),
        )
        val pz = graphic(PackItemSync.takeTheirs(kind, local, base, turned).apply(olderPz), "pzMarkers", JsonPrimitive("pz-1"))
        assertValue(json("""{"lat": 34.502, "lon": -84.102, "tipLat": 34.504, "tipLon": -84.102}"""), place(pz, "lat", "lon", "tipLat", "tipLon"), "the older PZ marker")
    }
}
