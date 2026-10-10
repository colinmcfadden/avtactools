package app.ezpztac.missionpacks

import app.ezpztac.missionpacks.PackFixtures.localId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An LZ/PZ a newer version of the web wrote keeps the fields this one does not know ([PackLz.carryUnknown]): the web drops them (the
 * `webBug` case in shared.json), and its first change would send null for each, for everyone in the pack. Android only, so held here.
 */
class PackLzCarryTest {
    private val kind = LzPackKind(PackFixtures.env())

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // An LZ/PZ in today's shape, with a field this version does not know at each level a shape is built at.
    private val newer = json(
        """
        {"schemaVersion": 2, "status": "analyzed", "weather": {"wind": "270/12"},
         "target": {"lat": 34.783817, "lon": -84.08219, "mgrs": "16S GD 66993 52949", "elevationFt": 4050},
         "mapData": {"zoom": 17}, "flightData": {"callSign": "HAWK 6"},
         "analysis": {"customLZ": null, "detectedLZ": [[34.7832, -84.0828], [34.7844, -84.0828], [34.7844, -84.0814]], "results": null,
                      "gridElevation": "4050", "latLong": "", "futureAnalysis": {"keep": [1, null]}},
         "graphics": {"doghouses": [], "helicopters": [{"id": 1, "lat": 34.7837, "lon": -84.0823, "heading": 270}], "pzMarkers": [],
                      "sectorsOfFire": [], "goArounds": [], "units": [], "measurements": [], "exportBox": null,
                      "futureGraphics": [{"id": "f-1"}]}}
        """,
    )

    private val item = PackItemView("lz-1", "lz", "LZ HAWK", newer)

    private fun at(doc: JsonElement, vararg path: String): JsonElement? =
        path.fold<String, JsonElement?>(doc) { node, key -> (node as? JsonObject)?.get(key) }

    @Test
    fun `fields a newer version wrote survive the shape, at every level`() {
        val shape = kind.currentShape(localId(item), item)
        assertEquals(newer["weather"], at(shape, "weather"))
        assertEquals(JsonPrimitive(4050), at(shape, "target", "elevationFt"))
        assertEquals(at(newer, "analysis", "futureAnalysis"), at(shape, "analysis", "futureAnalysis"))
        assertEquals(at(newer, "graphics", "futureGraphics"), at(shape, "graphics", "futureGraphics"))
        // In today's shape already: no reshape at all.
        assertTrue(PackDiff.diffData(kind.shared(newer), shape).isEmpty())
    }

    @Test
    fun `an edit sends its change and never a null for them`() {
        val opened = kind.fromItem(localId(item), item, null)
        val helicopter = opened.graphics.helicopters.single().jsonObject
        val moved = opened.copy(
            graphics = opened.graphics.copy(helicopters = listOf(JsonObject(helicopter + ("lat" to JsonPrimitive(34.7835))))),
        )
        val sent = PackEdit.composeEdit(
            uuid = item.uuid,
            base = EditVersion(item.name, kind.currentShape(localId(item), item)),
            mine = EditVersion(item.name, kind.docOf(moved, item.data)),
            item = item,
            shared = { kind.shared(item.data) },
            current = { kind.currentShape(localId(item), item) },
            describe = kind::describe,
            actor = "Sam B.",
        )!!
        assertEquals(1, sent.ops.size, "the move, and nothing else: ${sent.ops}")
        assertEquals("Sam B. moved Chalk 1 on LZ HAWK.", (sent.ops.single()["summary"] as JsonPrimitive).content)
        val after = PackFixtures.applyAll("lz", newer, sent.ops)
        for (path in listOf(listOf("weather"), listOf("target", "elevationFt"), listOf("analysis", "futureAnalysis"), listOf("graphics", "futureGraphics"))) {
            assertEquals(at(newer, *path.toTypedArray()), at(after, *path.toTypedArray()), "$path")
        }
    }

    @Test
    fun `what the editor's document is made from is the pack's data at the time, not whatever the pack has now`() {
        val opened = kind.fromItem(localId(item), item, null)
        val gone = JsonObject(newer - "weather")
        assertEquals(newer["weather"], at(kind.docOf(opened, newer), "weather"))
        assertNull(at(kind.docOf(opened, gone), "weather"))
        // With nothing to carry from, the shape is the diagram's own.
        assertEquals(PackLz.lzItemData(opened), kind.docOf(opened, null))
    }

    @Test
    fun `a name the web reads is never put back, at any level, nor a slope raster`() {
        // Every name the web reads at each level (shared.json's list, not the port's), a slope raster, and one name it does not read.
        fun level(name: String, given: Map<String, JsonElement> = emptyMap()): JsonObject {
            val fields = LinkedHashMap<String, JsonElement>()
            PackFixtures.lzReadNames.getValue(name).forEach { fields[it] = JsonPrimitive("x") }
            fields["terrainData"] = JsonPrimitive("RASTER")
            fields.putAll(given)
            fields["kept"] = JsonPrimitive(name)
            return JsonObject(fields)
        }
        val target = mapOf("lat" to JsonPrimitive(34.78), "lon" to JsonPrimitive(-84.08), "mgrs" to JsonPrimitive("16S GD 66993 52949"))
        val raw = level(
            "top",
            mapOf("status" to JsonPrimitive("analyzed"), "target" to level("target", target), "analysis" to level("analysis"), "graphics" to level("graphics")),
        )
        val item = PackItemView("lz-1", "lz", "LZ", raw)
        val shape = kind.currentShape(localId(item), item).jsonObject
        // The shape with nothing carried: what an item has whatever a newer version wrote.
        val bare = kind.docOf(kind.fromItem(localId(item), item, null), null).jsonObject
        for (name in listOf("top", "target", "analysis", "graphics")) {
            val carried = if (name == "top") shape else shape.getValue(name).jsonObject
            val own = if (name == "top") bare else bare.getValue(name).jsonObject
            assertEquals(setOf("kept"), carried.keys - own.keys, "$name: only the name the web does not read comes back")
            assertEquals(JsonPrimitive(name), carried["kept"], name)
            assertFalse("terrainData" in carried, "$name: no slope raster")
        }
    }

    @Test
    fun `nothing to carry leaves the document as it is`() {
        val doc = PackLz.lzItemData(kind.fromItem("pack:p:lz-1", item, null))
        assertSame(doc, PackLz.carryUnknown(null, doc))
        assertSame(doc, PackLz.carryUnknown(JsonNull, doc))
        assertSame(doc, PackLz.carryUnknown(Json.parseToJsonElement("[1]"), doc))
        assertSame(doc, PackLz.carryUnknown(doc, doc))
        // A level the shape does not have as an object (a target that is not a position) takes nothing.
        val noTarget = JsonObject(doc + ("target" to JsonNull))
        assertSame(noTarget, PackLz.carryUnknown(json("""{"target": {"lat": "north", "kept": 1}}"""), noTarget))
    }
}
