package app.ezpztac.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Turns whatever was saved into the current diagram: a current v2 diagram, the
 * flat snapshot `App.js` saved before diagrams were versioned, or an API record
 * wrapping either. A port of `normalizeLzDiagram` and its neighbours in
 * `useLzWorkspace.js`, held to `contracts/fixtures/workspace/diagram.json`.
 *
 * The native apps have to open every diagram the web has ever saved, so this reads
 * with the web's loose rules (see [JsValue]) rather than strict types. It copies
 * graphics without looking inside them, so fields it does not know survive.
 */
public object DiagramNormalizer {
    /**
     * The clock and id source, injectable so tests do not depend on either. The web
     * falls back to `new Date().toISOString()` and `crypto.randomUUID()` when a saved
     * document has no timestamp or id.
     */
    public class Environment(
        public val now: () -> String = { ISO_MILLIS.format(java.time.Instant.now()) },
        public val newId: () -> String = { UUID.randomUUID().toString() },
    )

    /** What the caller knows that overrides the document (`options` in the web). A null field means "not given". */
    public data class Options(
        val id: String? = null,
        val savedId: JsonElement? = null,
        val name: String? = null,
        val dirty: Boolean? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
    )

    // JavaScript's Date.toISOString: always milliseconds, always Z.
    private val ISO_MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private val EMPTY = JsonObject(emptyMap())

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject?.get2(key: String): JsonElement? = this?.get(key)

    /**
     * Normalizes both the existing `[lat, lon]` app state and object-style inputs.
     * [mgrs] is the grid to keep; the target's own `mgrs` is the fallback.
     * Null when there is no target or it is not a usable position.
     */
    public fun normalizeTarget(target: JsonElement?, mgrs: JsonElement? = JsonPrimitive("")): DiagramTarget? {
        if (!JsValue.truthy(target)) return null

        val lat: JsonElement?
        val lon: JsonElement?
        when (target) {
            is JsonArray -> { lat = target.getOrNull(0); lon = target.getOrNull(1) }
            is JsonObject -> {
                lat = JsValue.firstPresent(target["lat"], target["latitude"])
                lon = JsValue.firstPresent(target["lon"], target["lng"], target["longitude"])
            }
            else -> { lat = null; lon = null }                 // a number or string has no .lat
        }
        val parsedLat = JsValue.number(lat)
        val parsedLon = JsValue.number(lon)
        if (!parsedLat.isFinite() || !parsedLon.isFinite()) return null

        val given = (mgrs as? JsonPrimitive)?.takeIf { it.isString }?.content
        val own = (target.obj()?.get("mgrs") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return DiagramTarget(
            lat = parsedLat,
            lon = parsedLon,
            // `typeof mgrs === "string" && mgrs.trim()` keeps the string as given, untrimmed.
            mgrs = if (given != null && given.trim().isNotEmpty()) given else own ?: "",
        )
    }

    private fun cloneArray(value: JsonElement?): List<JsonElement> = (value as? JsonArray)?.toList() ?: emptyList()

    private fun normalizeGraphics(source: JsonObject): DiagramGraphics {
        val graphics = source["graphics"].obj() ?: source
        return DiagramGraphics(
            doghouses = cloneArray(graphics["doghouses"]),
            helicopters = cloneArray(graphics["helicopters"]),
            // `pzMarker` and `goAround` are the field names in the flat App snapshot;
            // new diagrams use the plurals. `??` falls through on null only, so a
            // present but non-array `pzMarkers` is an empty list, not a fallback.
            pzMarkers = cloneArray(JsValue.firstPresent(graphics["pzMarkers"], graphics["pzMarker"])),
            sectorsOfFire = cloneArray(graphics["sectorsOfFire"]),
            goArounds = cloneArray(JsValue.firstPresent(graphics["goArounds"], graphics["goAround"])),
            units = cloneArray(graphics["units"]),
            measurements = cloneArray(graphics["measurements"]),
            exportBox = JsValue.present(graphics["exportBox"]) ?: JsonNull,
        )
    }

    private fun normalizeAnalysis(source: JsonObject): DiagramAnalysis {
        val analysis = source["analysis"].obj() ?: source
        val defaults = DiagramAnalysis()
        return DiagramAnalysis(
            customLZ = JsValue.present(analysis["customLZ"]) ?: defaults.customLZ,
            detectedLZ = JsValue.present(analysis["detectedLZ"]) ?: defaults.detectedLZ,
            terrainData = JsValue.present(analysis["terrainData"]) ?: defaults.terrainData,
            results = JsValue.firstPresent(analysis["results"], analysis["analysisResults"]) ?: defaults.results,
            gridElevation = JsValue.present(analysis["gridElevation"]) ?: defaults.gridElevation,
            latLong = JsValue.present(analysis["latLong"]) ?: defaults.latLong,
        )
    }

    internal fun normalizeView(source: JsonObject): DiagramView {
        val view = source["view"].obj() ?: source
        val defaults = DiagramView()
        return DiagramView(
            mapStyle = JsValue.string(JsValue.present(view["mapStyle"])) ?: defaults.mapStyle,
            showLZOutline = (JsValue.present(view["showLZOutline"]) as? JsonPrimitive)?.let { JsValue.truthy(it) } ?: defaults.showLZOutline,
            showHeatmap = (JsValue.present(view["showHeatmap"]) as? JsonPrimitive)?.let { JsValue.truthy(it) } ?: defaults.showHeatmap,
        )
    }

    private fun deriveStatus(requested: JsonElement?, target: DiagramTarget?, analysis: DiagramAnalysis): DiagramStatus {
        if (target == null) return DiagramStatus.DRAFT
        val asked = (requested as? JsonPrimitive)?.takeIf { it.isString }?.content == "analyzed"
        // Analysis results (a detected LZ or computed results) make the diagram analysed whatever it claimed.
        return if (asked || JsValue.truthy(analysis.detectedLZ) || JsValue.truthy(analysis.results)) {
            DiagramStatus.ANALYZED
        } else {
            DiagramStatus.TARGETED
        }
    }

    /**
     * One versioned diagram from a current diagram or a legacy flat snapshot.
     * Anything that is not an object reads as an empty diagram.
     */
    public fun normalize(source: JsonElement?, options: Options = Options(), env: Environment = Environment()): Diagram {
        val input = source.obj() ?: EMPTY
        val target = normalizeTarget(
            JsValue.firstPresent(input["target"], input["targetLocation"]),
            JsValue.firstPresent(
                input["target"].obj().get2("mgrs"),
                input["mapData"].obj().get2("mgrs"),
                input["gridInput"],
                JsonPrimitive(""),
            ),
        )
        val analysis = normalizeAnalysis(input)
        val createdAt = options.createdAt
            ?: JsValue.string(JsValue.firstPresent(input["createdAt"], input["created_at"]))
            ?: env.now()
        val updatedAt = options.updatedAt
            ?: JsValue.string(JsValue.firstPresent(input["updatedAt"], input["updated_at"]))
            ?: createdAt
        val id = options.id ?: JsValue.string(input["id"]) ?: env.newId()

        return Diagram(
            id = id,
            savedId = options.savedId?.takeIf { it !is JsonNull } ?: JsValue.present(input["savedId"]) ?: JsonNull,
            name = options.name ?: JsValue.string(input["name"]) ?: "",
            dirty = options.dirty ?: JsValue.truthy(input["dirty"]),
            createdAt = createdAt,
            updatedAt = updatedAt,
            status = deriveStatus(input["status"], target, analysis),
            target = target,
            mapData = input["mapData"].obj() ?: EMPTY,
            flightData = input["flightData"].obj() ?: EMPTY,
            analysis = analysis,
            graphics = normalizeGraphics(input),
            view = normalizeView(input),
        )
    }

    /**
     * The current App save shape, or the API record wrapping it (`{ id, name,
     * created_at, updated_at, lz_data }`), as exactly one versioned diagram.
     */
    public fun normalizeLegacySnapshot(snapshot: JsonElement?, options: Options = Options(), env: Environment = Environment()): Diagram {
        val wrapper = snapshot.obj() ?: EMPTY
        val record = wrapper["lz_data"].obj()
        val isApiRecord = record != null
        return normalize(
            record ?: wrapper,
            Options(
                id = options.id ?: JsValue.string(wrapper["clientId"]),
                savedId = options.savedId?.takeIf { it !is JsonNull }
                    ?: (if (isApiRecord) JsValue.present(wrapper["id"]) else JsValue.present(wrapper["savedId"])),
                name = options.name ?: JsValue.string(wrapper["name"]) ?: "",
                dirty = options.dirty ?: false,
                createdAt = options.createdAt ?: if (isApiRecord) JsValue.string(wrapper["created_at"]) else null,
                updatedAt = options.updatedAt ?: if (isApiRecord) JsValue.string(wrapper["updated_at"]) else null,
            ),
            env,
        )
    }

    /**
     * A blank, target-bound diagram. Graphics are deliberately empty: defaults
     * (such as doghouses) are created only after analysis has succeeded for this
     * particular diagram. Null when [target] is not a usable position.
     */
    public fun fromTarget(
        target: JsonElement?,
        mgrs: String = "",
        id: String? = null,
        name: String = "",
        savedId: JsonElement? = null,
        mapData: JsonObject = EMPTY,
        view: JsonObject? = null,
        createdAt: String? = null,
        env: Environment = Environment(),
    ): Diagram? {
        val normalizedTarget = normalizeTarget(target, JsonPrimitive(mgrs)) ?: return null
        val timestamp = createdAt ?: env.now()
        val mapMgrs = normalizedTarget.mgrs.ifEmpty { JsValue.string(mapData["mgrs"])?.takeIf { it.isNotEmpty() } ?: "" }
        val document = JsonObject(
            buildMap {
                put("id", JsonPrimitive(id ?: env.newId()))
                put("savedId", savedId ?: JsonNull)
                put("name", JsonPrimitive(name))
                put("dirty", JsonPrimitive(true))
                put("createdAt", JsonPrimitive(timestamp))
                put("updatedAt", JsonPrimitive(timestamp))
                put("target", JsonObject(mapOf("lat" to JsonPrimitive(normalizedTarget.lat), "lon" to JsonPrimitive(normalizedTarget.lon), "mgrs" to JsonPrimitive(normalizedTarget.mgrs))))
                put("mapData", JsonObject(mapData + ("mgrs" to JsonPrimitive(mapMgrs))))
                if (view != null) put("view", view)
                put("status", JsonPrimitive("targeted"))
            },
        )
        return normalize(document, Options(id = id, createdAt = timestamp, updatedAt = timestamp), env)
    }

    /**
     * What is saved for a diagram: clean, and without the terrain raster, which is
     * regenerated from the analysis boundary when the diagram is opened.
     */
    public fun serialize(diagram: Diagram, includeTerrainData: Boolean = false): Diagram = diagram.copy(
        dirty = false,
        analysis = diagram.analysis.copy(terrainData = if (includeTerrainData) diagram.analysis.terrainData else JsonNull),
    )

    /**
     * One canonical session shape from anything a session could start from: nothing,
     * a workspace, a list of diagrams, a single diagram, or a legacy snapshot.
     * A repeated id gets `-2`, `-3` and so on, in order; the active diagram is the
     * one asked for, else the last.
     */
    public fun createWorkspace(source: JsonElement?, env: Environment = Environment()): Workspace {
        val raw: List<JsonElement> = when {
            source is JsonArray -> source.toList()
            source is JsonObject && source["diagrams"] is JsonArray -> (source["diagrams"] as JsonArray).toList()
            source is JsonObject && source["diagramOrder"] is JsonArray && source["diagramsById"] is JsonObject ->
                (source["diagramOrder"] as JsonArray).mapNotNull { id ->
                    (source["diagramsById"] as JsonObject)[(id as? JsonPrimitive)?.content ?: ""]
                        ?: JsonNull
                }
            source is JsonObject && (JsValue.truthy(source["targetLocation"]) || JsValue.truthy(source["target"]) || JsValue.truthy(source["graphics"])) ->
                listOf(source)
            else -> emptyList()
        }

        val byId = LinkedHashMap<String, Diagram>()
        val order = mutableListOf<String>()
        for (entry in raw) {
            if (!JsValue.truthy(entry)) continue                // `.filter(Boolean)`
            val normalized = normalize(entry, Options(dirty = JsValue.truthy(entry.obj()?.get("dirty"))), env)
            var id = normalized.id
            if (byId.containsKey(id)) {
                var suffix = 2
                var candidate = "$id-$suffix"
                while (byId.containsKey(candidate)) { suffix += 1; candidate = "$id-$suffix" }
                id = candidate
            }
            byId[id] = if (id == normalized.id) normalized else normalized.copy(id = id)
            order += id
        }

        val requested = (source.obj()?.get("activeDiagramId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val active = if (requested != null && byId.containsKey(requested)) requested else order.lastOrNull()
        return Workspace(diagramsById = byId, diagramOrder = order, activeDiagramId = active)
    }
}
