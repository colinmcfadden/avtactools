package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * What can be done to one LZ diagram: analyse it, edit its graphics, change its view or flight data, rename it. Each operation is a pure
 * function from a diagram to a diagram, the same transformations the web's `lzWorkspaceReducer` makes (`useLzWorkspace.js`), held to
 * `contracts/fixtures/workspace/ops.json`, so a diagram edited here is the diagram the web would have made.
 *
 * Two rules the web keeps are kept here, because they are what stop a document going wrong:
 *  - **Nothing is analysed without a target**, so a result always belongs to a place.
 *  - **Graphics are edited only after analysis** ([Diagram.canEditGraphics]): until then the diagram has no landing zone for them to sit in.
 * An operation that is refused returns the diagram it was given, so a caller can tell nothing happened by comparing.
 *
 * `updatedAt` and the dirty flag are not touched: the repository stamps the first when it saves, and whether there are unsaved changes is
 * the record's own business. Graphics stay opaque JSON, so a field a newer web release adds to a helicopter survives an edit made here.
 */
public object DiagramOps {
    /** The graphic collections an operation may name (the web's `LZ_GRAPHIC_COLLECTIONS`). `exportBox` is a single value, not a collection. */
    public val GRAPHIC_COLLECTIONS: List<String> =
        listOf("doghouses", "helicopters", "pzMarkers", "sectorsOfFire", "goArounds", "units", "measurements")

    private val NO_OBJECT = JsonObject(emptyMap())

    /** `cloneObject`: a non-object is nothing. */
    private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: NO_OBJECT

    private fun cloneArray(value: JsonElement?): List<JsonElement> = (value as? JsonArray)?.toList() ?: emptyList()

    // -- Analysis ---------------------------------------------------------------------------------------------

    /** The analysis came back: the diagram is analysed, and what it found is merged over what it had. Refused with no target. */
    public fun completeAnalysis(diagram: Diagram, analysis: JsonElement?): Diagram {
        if (diagram.target == null) return diagram
        return diagram.copy(status = DiagramStatus.ANALYZED, analysis = merge(diagram.analysis, analysis))
    }

    /** Part of an analysis written in place (a hand-drawn boundary): the status is left as it is. Refused with no target. */
    public fun setAnalysisDraft(diagram: Diagram, analysis: JsonElement?): Diagram {
        if (diagram.target == null) return diagram
        return diagram.copy(analysis = merge(diagram.analysis, analysis))
    }

    /** Back to targeted, with no analysis. The graphics stay (the web keeps them). Refused with no target. */
    public fun resetAnalysis(diagram: Diagram): Diagram {
        if (diagram.target == null) return diagram
        return diagram.copy(status = DiagramStatus.TARGETED, analysis = DiagramAnalysis())
    }

    /** The slope raster, which is runtime only (never saved). Refused with no target. */
    public fun setRuntimeTerrainData(diagram: Diagram, terrainData: JsonElement): Diagram {
        if (diagram.target == null || diagram.analysis.terrainData == terrainData) return diagram
        return diagram.copy(analysis = diagram.analysis.copy(terrainData = terrainData))
    }

    /** `{...analysis, ...patch}` over the fields a diagram keeps: a field the patch names replaces it, null included. */
    private fun merge(current: DiagramAnalysis, patch: JsonElement?): DiagramAnalysis {
        val p = patch.asObject()
        return current.copy(
            customLZ = p["customLZ"] ?: current.customLZ,
            detectedLZ = p["detectedLZ"] ?: current.detectedLZ,
            terrainData = p["terrainData"] ?: current.terrainData,
            results = p["results"] ?: current.results,
            gridElevation = p["gridElevation"] ?: current.gridElevation,
            latLong = p["latLong"] ?: current.latLong,
        )
    }

    /**
     * What the web does when an analysis comes back (`handleAnalysisComplete` in `App.js`): complete it, and make the standard SP and RP
     * doghouses **once**, only if the diagram has none. A later analysis never makes them again.
     */
    public fun afterAnalysis(diagram: Diagram, analysis: JsonElement?): Diagram {
        val target = diagram.target ?: return diagram
        val completed = completeAnalysis(diagram, analysis)
        if (diagram.graphics.doghouses.isNotEmpty()) return completed
        return setGraphicItems(completed, "doghouses", defaultDoghouses(target.lat, target.lon, diagram.id))
    }

    // -- Graphics ---------------------------------------------------------------------------------------------

    private fun items(graphics: DiagramGraphics, collection: String): List<JsonElement>? = when (collection) {
        "doghouses" -> graphics.doghouses
        "helicopters" -> graphics.helicopters
        "pzMarkers" -> graphics.pzMarkers
        "sectorsOfFire" -> graphics.sectorsOfFire
        "goArounds" -> graphics.goArounds
        "units" -> graphics.units
        "measurements" -> graphics.measurements
        else -> null
    }

    private fun withItems(graphics: DiagramGraphics, collection: String, items: List<JsonElement>): DiagramGraphics = when (collection) {
        "doghouses" -> graphics.copy(doghouses = items)
        "helicopters" -> graphics.copy(helicopters = items)
        "pzMarkers" -> graphics.copy(pzMarkers = items)
        "sectorsOfFire" -> graphics.copy(sectorsOfFire = items)
        "goArounds" -> graphics.copy(goArounds = items)
        "units" -> graphics.copy(units = items)
        "measurements" -> graphics.copy(measurements = items)
        else -> graphics
    }

    /**
     * Replaces the collections the patch names, and only those. The web's older names (`pzMarker`, `goAround`) are read as a fallback
     * for a *named* plural, as the normalizer does, but a patch that names only an old name changes nothing. Refused before analysis.
     */
    public fun setGraphics(diagram: Diagram, patch: JsonElement?): Diagram {
        if (!diagram.canEditGraphics) return diagram
        val p = patch.asObject()
        var graphics = diagram.graphics
        for (name in GRAPHIC_COLLECTIONS) {
            if (name !in p) continue
            val source = when (name) {
                "pzMarkers" -> JsValue.firstPresent(p["pzMarkers"], p["pzMarker"])
                "goArounds" -> JsValue.firstPresent(p["goArounds"], p["goAround"])
                else -> p[name]
            }
            graphics = withItems(graphics, name, cloneArray(source))
        }
        if ("exportBox" in p) graphics = graphics.copy(exportBox = JsValue.present(p["exportBox"]) ?: JsonNull)
        return diagram.copy(graphics = graphics)
    }

    /** One collection set whole. A collection that does not exist, or a diagram not yet analysed, is refused. Items that are not objects are kept. */
    public fun setGraphicCollection(diagram: Diagram, collection: String, items: JsonElement?): Diagram {
        if (!diagram.canEditGraphics || collection !in GRAPHIC_COLLECTIONS) return diagram
        return diagram.copy(graphics = withItems(diagram.graphics, collection, cloneArray(items)))
    }

    /** The same, for items already in hand (a JSON array is also a list, so this one has a name of its own). */
    public fun setGraphicItems(diagram: Diagram, collection: String, items: List<JsonElement>): Diagram {
        if (!diagram.canEditGraphics || collection !in GRAPHIC_COLLECTIONS) return diagram
        return diagram.copy(graphics = withItems(diagram.graphics, collection, items))
    }

    /**
     * Adds a graphic, or merges into the one with the same `id` (fields it does not mention are kept). An item with no `id` is always added.
     * An item that is not an object is refused.
     */
    public fun upsertGraphic(diagram: Diagram, collection: String, item: JsonElement): Diagram {
        if (!diagram.canEditGraphics || item !is JsonObject) return diagram
        val current = items(diagram.graphics, collection) ?: return diagram
        val id = item["id"]
        val index = if (id == null) -1 else current.indexOfFirst { sameId(it, id) }
        val next = if (index >= 0) {
            current.mapIndexed { i, existing -> if (i == index) JsonObject((existing as JsonObject) + item) else existing }
        } else {
            current + item
        }
        return diagram.copy(graphics = withItems(diagram.graphics, collection, next))
    }

    /** Merges [patch] into the graphic with [id]. A missing id, or a patch that is not an object, changes nothing. */
    public fun patchGraphic(diagram: Diagram, collection: String, id: JsonElement?, patch: JsonElement?): Diagram {
        if (!diagram.canEditGraphics || id == null) return diagram
        val current = items(diagram.graphics, collection) ?: return diagram
        val changes = patch.asObject()
        val next = current.map { if (sameId(it, id)) JsonObject((it as JsonObject) + changes) else it }
        return diagram.copy(graphics = withItems(diagram.graphics, collection, next))
    }

    public fun removeGraphic(diagram: Diagram, collection: String, id: JsonElement?): Diagram {
        if (!diagram.canEditGraphics || id == null) return diagram
        val current = items(diagram.graphics, collection) ?: return diagram
        return diagram.copy(graphics = withItems(diagram.graphics, collection, current.filterNot { sameId(it, id) }))
    }

    /** `item?.id === id`: only an object with that very id, as JavaScript's strict equality sees it (the number 1 is not the text "1"). */
    private fun sameId(item: JsonElement, id: JsonElement): Boolean {
        val own = (item as? JsonObject)?.get("id") ?: return false
        if (own is JsonNull || id is JsonNull) return own is JsonNull && id is JsonNull
        if (own !is JsonPrimitive || id !is JsonPrimitive) return false                // two parsed objects are never the same reference
        return when {
            own.isString || id.isString -> own.isString && id.isString && own.content == id.content
            own.booleanOrNull != null || id.booleanOrNull != null -> own.booleanOrNull != null && own.booleanOrNull == id.booleanOrNull
            else -> own.doubleOrNull != null && own.doubleOrNull == id.doubleOrNull
        }
    }

    // -- Flight data, view, name: allowed at any stage -------------------------------------------------------------

    /** Merges into the flight data. A patch that changes nothing returns the same diagram. */
    public fun setFlightData(diagram: Diagram, patch: JsonElement?): Diagram {
        val merged = JsonObject(diagram.flightData + patch.asObject())
        return if (merged == diagram.flightData) diagram else diagram.copy(flightData = merged)
    }

    /** Merges into the view (base map and overlays). A value the document cannot hold is read as the normalizer reads it. */
    public fun setView(diagram: Diagram, patch: JsonElement?): Diagram {
        val current = JsonObject(
            mapOf(
                "mapStyle" to JsonPrimitive(diagram.view.mapStyle),
                "showLZOutline" to JsonPrimitive(diagram.view.showLZOutline),
                "showHeatmap" to JsonPrimitive(diagram.view.showHeatmap),
            ),
        )
        return diagram.copy(view = DiagramNormalizer.normalizeView(JsonObject(current + patch.asObject())))
    }

    public fun setName(diagram: Diagram, name: String?): Diagram = diagram.copy(name = name ?: "")

    // -- Default doghouses ------------------------------------------------------------------------------------

    private const val DOGHOUSE_OFFSET = 0.003

    /** The standard SP and RP doghouses, a little west of the target, with the ids namespaced by [namespace] (the diagram's id). */
    public fun defaultDoghouses(lat: Double, lon: Double, namespace: String): List<JsonElement> =
        defaultDoghouses(JsonArray(listOf(JsonPrimitive(lat), JsonPrimitive(lon))), JsonPrimitive(namespace))

    /**
     * `createDefaultDoghouses`, taking the target in any of the shapes the web accepts (`[lat, lon]`, `{lat, lon}`, `{lat, lng}`) and an
     * optional namespace. A target that is not a position gives none. With no namespace the ids are made from the position instead.
     */
    public fun defaultDoghouses(target: JsonElement?, namespace: JsonElement?): List<JsonElement> {
        val (latSource, lonSource) = when (target) {
            is JsonArray -> target.getOrNull(0) to target.getOrNull(1)
            is JsonObject -> target["lat"] to JsValue.firstPresent(target["lng"], target["lon"])
            else -> null to null
        }
        val lat = JsValue.number(latSource)
        val lon = JsValue.number(lonSource)
        if (!lat.isFinite() || !lon.isFinite()) return emptyList()

        val fallback = "doghouse-${toFixed(lat, 6)}-${toFixed(lon, 6)}"
        val chosen = if (JsValue.truthy(namespace)) text(namespace) else fallback
        val prefix = safe(chosen)
        fun doghouse(id: String, role: String, lat: Double, lon: Double, label: String, time: String, dist: String, speed: String) = JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive("$prefix-$id"), "role" to JsonPrimitive(role), "lat" to JsonPrimitive(lat), "lon" to JsonPrimitive(lon),
                "id_val" to JsonPrimitive(label), "heading" to JsonPrimitive("000°"), "time" to JsonPrimitive(time),
                "dist" to JsonPrimitive(dist), "airspeed" to JsonPrimitive(speed),
            ),
        )
        return listOf(
            doghouse("sp1", "takeoff", lat, lon - DOGHOUSE_OFFSET, "[SP1]", "01+57", "3.13km", "60 KIAS"),
            doghouse("rp1", "landing", lat - DOGHOUSE_OFFSET / 2, lon - DOGHOUSE_OFFSET, "[RP1]", "02+10", "5.20km", "40 KIAS"),
        )
    }

    /** `String(value)` for what a namespace can be: text as it is, a whole number without a fraction. */
    private fun text(value: JsonElement?): String {
        val primitive = value as? JsonPrimitive ?: return value.toString()
        val number = primitive.takeIf { !it.isString }?.doubleOrNull
        return if (number != null && number == Math.rint(number) && abs(number) < 1e21) number.toLong().toString() else primitive.content
    }

    /** `replace(/[^a-zA-Z0-9_-]/g, "_")`. JavaScript's pattern works on UTF-16 units, so a character outside the basic plane becomes two. */
    private fun safe(text: String): String = buildString {
        for (c in text) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_')
    }

    /** JavaScript's `toFixed`: the exact binary value, ties away from zero. */
    private fun toFixed(value: Double, places: Int): String {
        val body = BigDecimal(abs(value)).setScale(places, RoundingMode.HALF_UP).toPlainString()
        return if (value < 0) "-$body" else body
    }
}
