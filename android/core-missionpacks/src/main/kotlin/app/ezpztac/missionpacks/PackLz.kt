package app.ezpztac.missionpacks

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramJson
import app.ezpztac.model.DiagramNormalizer
import app.ezpztac.model.JsNumber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An LZ/PZ diagram as a mission pack item: the web's `packLz.js`, held to `contracts/fixtures/packs/shared.json` and
 * `describe.json`. The item's data is the document the library saves, less what is each person's own:
 *
 *  - their view of it (base map, LZ outline, slope map), so one person switching to the sectional does not switch everyone;
 *  - which of their own records it was, whether they have unsaved changes, and the times;
 *  - the slope raster, which is redrawn from the boundary and never saved anywhere;
 *  - its name, which is the item's own (`item.rename`).
 */
public object PackLz {
    public val OWN_FIELDS: List<String> = listOf("id", "savedId", "dirty", "createdAt", "updatedAt", "view", "name")

    private const val SLOPE_RASTER = "terrainData"

    // The names the web's normalizeLzDiagram reads, at each level an item's shape is built at (shared.json's description lists them).
    // Each is either in the shape or left out of it on purpose (an own field, the slope raster, an old name read into its new place),
    // so [carryUnknown] must never put one back: it would undo the reshape, or bring an old name back beside its new one.
    private val ANALYSIS_NAMES = listOf("customLZ", "detectedLZ", "terrainData", "results", "analysisResults", "gridElevation", "latLong")
    private val GRAPHICS_NAMES = listOf(
        "doghouses", "helicopters", "pzMarkers", "pzMarker", "sectorsOfFire", "goArounds", "goAround", "units", "measurements", "exportBox",
    )
    private val VIEW_NAMES = listOf("mapStyle", "showLZOutline", "showHeatmap")
    private val TOP_NAMES: Set<String> = (
        listOf(
            "schemaVersion", "status", "target", "targetLocation", "gridInput", "mapData", "flightData", "analysis", "graphics",
            "id", "savedId", "dirty", "createdAt", "updatedAt", "view", "name", "created_at", "updated_at",
        ) + ANALYSIS_NAMES + GRAPHICS_NAMES + VIEW_NAMES
        ).toSet()
    private val TARGET_NAMES = setOf("lat", "lon", "mgrs", "latitude", "lng", "longitude")

    /**
     * The part of a diagram document a pack shares (`sharedLzData`): the top-level own fields removed whatever their value, and the
     * analysis's slope raster when the analysis is an object. The same names deeper in are kept, and so is everything else. No document
     * is an empty one; one that is not an object (a list, text, a number, false) is given back as it is.
     */
    public fun sharedLzData(data: JsonElement?): JsonElement {
        if (data == null || data is JsonNull) return JsonObject(emptyMap())
        if (data !is JsonObject) return data
        val shared = LinkedHashMap(data)
        OWN_FIELDS.forEach { shared.remove(it) }
        val analysis = shared["analysis"]
        if (analysis is JsonObject && SLOPE_RASTER in analysis) shared["analysis"] = JsonObject(analysis - SLOPE_RASTER)
        return JsonObject(shared)
    }

    /** A diagram's data as its pack item has it (`lzItemData`): saved clean, without what is each person's own. */
    public fun lzItemData(diagram: Diagram): JsonObject =
        sharedLzData(DiagramJson.encode(DiagramNormalizer.serialize(diagram))) as JsonObject

    /**
     * The diagram an editor works on for [item] (`lzDiagramFromItem`): its data read as the web reads a saved diagram, under the editor's
     * id [localId] ([PackRef.localId]) and the item's name, never saved as a record of anyone's own.
     */
    public fun lzDiagramFromItem(localId: String, item: PackItemView, env: DiagramNormalizer.Environment): Diagram {
        val input = Js.spread(item.data)
        input["id"] = JsonPrimitive(localId)
        input["name"] = JsonPrimitive(item.name)
        input["savedId"] = JsonNull
        return DiagramNormalizer.normalize(JsonObject(input), DiagramNormalizer.Options(dirty = false), env)
    }

    /**
     * [doc] (an item's shape) with the fields of [raw] (the item's data as the pack has it) that this version does not know put back:
     * at the top, in the target, the analysis and the graphics object, each only where [doc] has that object and lacks the field.
     * **Not like the web**, which drops them (shared.json marks the case `webBug`): its first change to an item a newer version wrote
     * would send null for each of them, for everyone in the pack. A name the web reads is never put back, nor any slope raster.
     */
    public fun carryUnknown(raw: JsonElement?, doc: JsonObject): JsonObject {
        if (raw !is JsonObject) return doc
        var out = carried(raw, doc, TOP_NAMES)
        for ((level, names) in listOf("target" to TARGET_NAMES, "analysis" to ANALYSIS_NAMES.toSet(), "graphics" to GRAPHICS_NAMES.toSet())) {
            val from = raw[level] as? JsonObject ?: continue
            val into = out[level] as? JsonObject ?: continue
            val merged = carried(from, into, names)
            if (merged !== into) out = JsonObject(LinkedHashMap(out).apply { put(level, merged) })
        }
        return out
    }

    private fun carried(from: JsonObject, into: JsonObject, known: Set<String>): JsonObject {
        val unknown = from.keys.filter { it !in known && it != SLOPE_RASTER && it !in into }
        if (unknown.isEmpty()) return into
        val fields = LinkedHashMap(into)
        unknown.forEach { fields[it] = from.getValue(it) }
        return JsonObject(fields)
    }

    // -- What a change was, in words, for the pack's history ----------------------------------------------------------------

    private class Collection(val name: String, val noun: (JsonElement, Int) -> String)

    private val COLLECTIONS = listOf(
        Collection("helicopters") { _, index -> "Chalk ${index + 1}" },
        Collection("pzMarkers") { _, _ -> "a PZ marker" },
        Collection("sectorsOfFire") { element, index ->
            val name = Js.prop(element, "name")
            "sector ${if (Js.truthy(name)) Js.text(name) else (index + 1).toString()}"
        },
        Collection("goArounds") { _, _ -> "a go-around" },
        Collection("units") { element, _ ->
            val designation = Js.prop(element, "uniqueDesignation")
            if (Js.truthy(designation)) "unit ${Js.text(designation)}" else "a unit"
        },
        Collection("doghouses") { element, _ ->
            val label = Js.prop(element, "label")
            if (Js.truthy(label)) "doghouse ${Js.text(label)}" else "a doghouse"
        },
        Collection("measurements") { _, _ -> "a measurement" },
    )

    private val POSITION_KEYS = setOf("lat", "lon", "lng", "position", "points", "center", "start", "end", "tip", "anchor")
    private val TURN_KEYS = listOf("heading", "rotation", "direction", "bearing")

    private class Followed(val element: JsonElement, val index: Int)

    // `new Map(list.filter((e) => e && e.id !== undefined).map((e, i) => [e.id, {element: e, index: i}]))`: an id twice keeps the place
    // of the first and stands for the last.
    private fun byId(list: JsonElement?, unique: () -> String): LinkedHashMap<String, Followed> {
        val map = LinkedHashMap<String, Followed>()
        val followed = (list as? JsonArray).orEmpty().filter { Js.truthy(it) && Js.prop(it, "id") != null }
        followed.forEachIndexed { i, element -> map[Js.mapKey(Js.prop(element, "id"), unique)] = Followed(element, i) }
        return map
    }

    // The keys of either version whose values differ as JSON.
    private fun changedKeys(before: JsonElement?, after: JsonElement?): List<String> =
        LinkedHashSet(Js.keys(before) + Js.keys(after)).filter { !PackDiff.sameData(Js.prop(before, it), Js.prop(after, it)) }

    private fun graphicPhrases(before: JsonElement?, after: JsonElement?, lz: String, unique: () -> String): List<String> {
        val phrases = ArrayList<String>()
        for (collection in COLLECTIONS) {
            val was = byId(Js.prop(Js.prop(before, "graphics"), collection.name), unique)
            val now = byId(Js.prop(Js.prop(after, "graphics"), collection.name), unique)
            for ((id, entry) in now) {
                val element = entry.element
                val old = was[id]
                if (old == null) {
                    phrases += "added ${collection.noun(element, entry.index)} to $lz"
                    continue
                }
                val keys = changedKeys(old.element, element)
                if (keys.isEmpty()) continue
                val what = collection.noun(element, entry.index)
                phrases += when {
                    keys.any { it in POSITION_KEYS } -> "moved $what on $lz"
                    keys.any { it in TURN_KEYS } -> {
                        val heading = TURN_KEYS.map { Js.prop(element, it) }.firstOrNull { value ->
                            Js.toNumber(value).isFinite() && value !is JsonNull && !(value is JsonPrimitive && value.isString && value.content.isEmpty())
                        }
                        if (heading != null) "turned $what to ${JsNumber.toText(Js.round(Js.toNumber(heading)))}° on $lz" else "turned $what on $lz"
                    }
                    else -> "changed $what on $lz"
                }
            }
            for ((id, entry) in was) {
                if (id !in now) phrases += "removed ${collection.noun(entry.element, entry.index)} from $lz"
            }
        }
        return phrases
    }

    private fun flightPhrases(before: JsonElement?, after: JsonElement?, lz: String): List<String> {
        val was = Js.prop(before, "flightData")
        val now = Js.prop(after, "flightData")
        val keys = changedKeys(was, now)
        if (keys.isEmpty()) return emptyList()
        for ((key, words) in listOf("landing_hdg" to "the landing heading", "takeoff_hdg" to "the takeoff heading")) {
            val value = Js.prop(now, key)
            if (key in keys && Js.truthy(value)) return listOf("changed $words to ${Js.text(value)} on $lz")
        }
        return listOf("changed the flight data on $lz")
    }

    /**
     * One sentence for the pack's history about a change to an LZ item ("Sam B. moved Chalk 2 on LZ IBIS."): `describeLzChange`, held
     * to describe.json's `lz` cases. [before] and [after] are the item's shared data; the most telling change is named. Cut to 300
     * UTF-16 units, never through a character ([Js.cut]; the web can leave half an emoji, which the server cannot store).
     */
    public fun describeLzChange(before: JsonElement?, after: JsonElement?, name: String?, actor: String?): String {
        val lz = name?.takeIf { it.isNotEmpty() } ?: "the LZ/PZ"
        var refs = 0
        val unique = { (refs++).toString() }
        val phrases = ArrayList<String>()
        if (!Js.strictEquals(Js.prop(before, "status"), ANALYZED) && Js.strictEquals(Js.prop(after, "status"), ANALYZED)) {
            phrases += "analyzed $lz"
        }
        if (!PackDiff.sameData(Js.prop(before, "target"), Js.prop(after, "target"))) {
            phrases += if (Js.truthy(Js.prop(after, "target"))) "moved the target of $lz" else "cleared the target of $lz"
        }
        val analysisBefore = Js.prop(before, "analysis")
        val analysisAfter = Js.prop(after, "analysis")
        if (!PackDiff.sameData(Js.prop(analysisBefore, "customLZ"), Js.prop(analysisAfter, "customLZ"))) {
            phrases += "drew the boundary of $lz"
        } else if (!PackDiff.sameData(Js.prop(analysisBefore, "detectedLZ"), Js.prop(analysisAfter, "detectedLZ"))) {
            phrases += "changed the boundary of $lz"
        }
        phrases += graphicPhrases(before, after, lz, unique)
        phrases += flightPhrases(before, after, lz)
        if (!PackDiff.sameData(Js.prop(Js.prop(before, "graphics"), "exportBox"), Js.prop(Js.prop(after, "graphics"), "exportBox"))) {
            phrases += "set the LZ card area on $lz"
        }
        val what = phrases.firstOrNull() ?: "edited $lz"
        return Js.cut("${actor?.takeIf { it.isNotEmpty() } ?: "Someone"} $what.", MAX_SENTENCE)
    }

    /** The longest history sentence, in UTF-16 units as the web counts them. */
    public const val MAX_SENTENCE: Int = 300

    private val ANALYZED = JsonPrimitive("analyzed")
}
