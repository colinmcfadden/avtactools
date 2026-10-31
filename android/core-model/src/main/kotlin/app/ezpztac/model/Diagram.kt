package app.ezpztac.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The saved LZ/PZ diagram, in the web's shape (`useLzWorkspace.js`, schema 2).
 *
 * A diagram made on a tablet opens on the web and back, so field names are the
 * web's, camelCase. What the model does not need to understand stays raw JSON:
 * each graphic is an opaque object, so a field a newer web release adds to a
 * helicopter or a sector survives a round trip through an older app.
 */

public const val DIAGRAM_SCHEMA_VERSION: Int = 2

@Serializable
public enum class DiagramStatus {
    @SerialName("draft") DRAFT,
    @SerialName("targeted") TARGETED,
    @SerialName("analyzed") ANALYZED,
}

/** The point the LZ is planned around. [mgrs] is whatever the user typed, not recomputed. */
@Serializable
public data class DiagramTarget(val lat: Double, val lon: Double, val mgrs: String = "")

/**
 * Analysis inputs and results. Every value is opaque JSON: the boundary polygons
 * and results have shapes only the analysis code reads, and [terrainData] is a
 * (possibly base64) raster that is runtime-only and never saved.
 */
@Serializable
public data class DiagramAnalysis(
    val customLZ: JsonElement = JsonNull,
    val detectedLZ: JsonElement = JsonNull,
    val terrainData: JsonElement = JsonNull,
    val results: JsonElement = JsonNull,
    val gridElevation: JsonElement = JsonPrimitive(""),
    val latLong: JsonElement = JsonPrimitive(""),
)

/** Each collection holds opaque graphics (non-object entries too: the web keeps them). */
@Serializable
public data class DiagramGraphics(
    val doghouses: List<JsonElement> = emptyList(),
    val helicopters: List<JsonElement> = emptyList(),
    val pzMarkers: List<JsonElement> = emptyList(),
    val sectorsOfFire: List<JsonElement> = emptyList(),
    val goArounds: List<JsonElement> = emptyList(),
    val units: List<JsonElement> = emptyList(),
    val measurements: List<JsonElement> = emptyList(),
    val exportBox: JsonElement = JsonNull,
)

@Serializable
public data class DiagramView(
    val mapStyle: String = "satellite",
    val showLZOutline: Boolean = true,
    val showHeatmap: Boolean = false,
)

@Serializable
public data class Diagram(
    val schemaVersion: Int = DIAGRAM_SCHEMA_VERSION,
    /** The client's id, a UUID, assigned at creation so a diagram has an identity before the server has seen it. */
    val id: String,
    /** The server's record id once saved: a number, kept as the JSON it arrived as. */
    val savedId: JsonElement = JsonNull,
    val name: String = "",
    val dirty: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val status: DiagramStatus = DiagramStatus.DRAFT,
    val target: DiagramTarget? = null,
    val mapData: JsonObject = JsonObject(emptyMap()),
    val flightData: JsonObject = JsonObject(emptyMap()),
    val analysis: DiagramAnalysis = DiagramAnalysis(),
    val graphics: DiagramGraphics = DiagramGraphics(),
    val view: DiagramView = DiagramView(),
) {
    /** `canAnalyzeLzDiagram`: a diagram needs a target before it can be analysed. */
    public val canAnalyze: Boolean get() = target != null

    /** `canEditLzDiagramGraphics`: graphics are placed only after a successful analysis. */
    public val canEditGraphics: Boolean get() = target != null && status == DiagramStatus.ANALYZED
}

/** A session's diagrams, in the order they were opened, and which one is showing. */
@Serializable
public data class Workspace(
    val schemaVersion: Int = DIAGRAM_SCHEMA_VERSION,
    val diagramsById: Map<String, Diagram> = emptyMap(),
    val diagramOrder: List<String> = emptyList(),
    val activeDiagramId: String? = null,
)
