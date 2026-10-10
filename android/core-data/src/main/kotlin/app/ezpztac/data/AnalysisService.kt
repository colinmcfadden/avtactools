package app.ezpztac.data

import app.ezpztac.geo.CoordinateParser
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramTarget
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiJson
import app.ezpztac.network.ApiException
import app.ezpztac.network.FieldAnalysis
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.TerrainAnalysis
import app.ezpztac.network.analyzeField
import app.ezpztac.network.terrainAnalysis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.coroutines.CoroutineContext

/** The two server calls an analysis makes. [ApiClient] in the app; a stand-in in tests. Both are heavy work for the server. */
public interface TerrainApi {
    public suspend fun analyzeField(at: LatLon): FieldAnalysis
    public suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis
}

/** [TerrainApi] over the real client. */
public class ApiClientTerrainApi(private val client: ApiClient) : TerrainApi {
    override suspend fun analyzeField(at: LatLon): FieldAnalysis = client.analyzeField(at)
    override suspend fun terrainAnalysis(polygon: List<LatLon>, landingHeadingDeg: Double?): TerrainAnalysis =
        client.terrainAnalysis(polygon, landingHeadingDeg)
}

/** Where an analysis has got to. One runs at a time (the server runs one at a time, and a person asks for one at a time). */
public sealed interface AnalysisStatus {
    public data object Idle : AnalysisStatus

    public data class Running(val diagramId: String, val stage: Stage) : AnalysisStatus {
        public enum class Stage { FINDING_AREA, MEASURING_SLOPE }
    }

    /** Stopped, with the reason in words for the person. The diagram is as it was. */
    public data class Failed(val diagramId: String, val message: String) : AnalysisStatus
}

/** The slope over a diagram's boundary. Runtime only, never saved: it is made again from the boundary (the web does the same). */
public sealed interface SlopeState {
    public data object Loading : SlopeState
    public data class Ready(val boundaryKey: String, val analysis: TerrainAnalysis) : SlopeState

    /** The server could not measure it (no terrain data there, or no signal). The analysis itself stands. */
    public data class Unavailable(val message: String) : SlopeState
}

/**
 * Analysing a landing zone online: the area around the target (`/api/analyze-field`), then the slope over it (`/api/terrain-analysis`).
 * Port of the web's `useTerrain` and `handleAnalysisComplete`.
 *
 * **A result belongs to the diagram it was asked for.** The answer takes many seconds, and in that time the person may open another
 * diagram or delete this one; it is applied to the diagram named when the request began — the open one if that is still it, otherwise
 * its stored record — or dropped if it is gone. It never lands on whichever diagram happens to be open when it arrives.
 *
 * An analysis is not an edit to take back: it is applied quietly ([DiagramSession.update]). If the area cannot be found nothing changes.
 * If the slope cannot be measured the analysis still stands and [slopes] says so.
 *
 * The diagram is read and written through the session ([DiagramSession.document], [DiagramSession.update]), so a mission pack's LZ/PZ is analysed
 * as a library one is, and its result goes to the pack's item: to the editor when it is open, else to the item itself, while its pack is open.
 */
public class AnalysisService(
    private val api: TerrainApi,
    private val session: DiagramSession,
    private val scope: CoroutineScope,
    /** Where a result is applied to a diagram: the thread that edits (the main one in the app). */
    private val applyContext: CoroutineContext,
) {
    private val _status = MutableStateFlow<AnalysisStatus>(AnalysisStatus.Idle)
    public val status: StateFlow<AnalysisStatus> = _status.asStateFlow()

    private val _slopes = MutableStateFlow<Map<String, SlopeState>>(emptyMap())

    /** By diagram id. */
    public val slopes: StateFlow<Map<String, SlopeState>> = _slopes.asStateFlow()

    private var job: Job? = null

    /** Analyses diagram [diagramId]. Ignored while one is running. */
    public fun analyze(diagramId: String) {
        if (_status.value is AnalysisStatus.Running) return
        _status.value = AnalysisStatus.Running(diagramId, AnalysisStatus.Running.Stage.FINDING_AREA)
        job = scope.launch {
            try {
                run(diagramId)
            } catch (e: CancellationException) {
                _status.value = AnalysisStatus.Idle
                throw e
            } catch (e: ApiException) {
                _status.value = AnalysisStatus.Failed(diagramId, words(e))
            } catch (e: Exception) {
                _status.value = AnalysisStatus.Failed(diagramId, "The analysis could not be completed.")
            }
        }
    }

    /** Stops waiting for the server. The diagram is as it was (or, past the area, analysed with no slope yet). */
    public fun cancel() {
        job?.cancel()
        job = null
        _status.value = AnalysisStatus.Idle
    }

    /** The person has read why it failed. */
    public fun dismiss() {
        if (_status.value is AnalysisStatus.Failed) _status.value = AnalysisStatus.Idle
    }

    private suspend fun run(diagramId: String) {
        val diagram = load(diagramId) ?: return fail(diagramId, "That diagram is no longer here.")
        val target = diagram.target ?: return fail(diagramId, "Set a target on the map before analysing.")

        val field = api.analyzeField(LatLon(target.lat, target.lon))
        val patch = patch(diagram, target, field)
        val applied = withContext(applyContext) { session.update(diagramId) { DiagramOps.afterAnalysis(it, patch) } }
        if (!applied) {
            _status.value = AnalysisStatus.Idle                                  // deleted while it ran: nothing to put it on
            return
        }
        _status.value = AnalysisStatus.Running(diagramId, AnalysisStatus.Running.Stage.MEASURING_SLOPE)
        measure(diagramId, patch["detectedLZ"])
        _status.value = AnalysisStatus.Idle
    }

    /**
     * Measures the slope over [diagram]'s boundary if it has one and it has not been measured already (opening an analysed diagram, or a
     * changed boundary). A call that fails is remembered, not thrown: the overlay is a nicety, the analysis stands without it.
     */
    public fun ensureSlope(diagram: Diagram) {
        val polygon = DiagramGeometry.boundary(diagram).takeIf { it.isNotEmpty() } ?: return
        val key = keyOf(polygon)
        val known = _slopes.value[diagram.id]
        if (known is SlopeState.Loading || (known is SlopeState.Ready && known.boundaryKey == key)) return
        _slopes.update { it + (diagram.id to SlopeState.Loading) }
        scope.launch {
            try {
                _slopes.update { it + (diagram.id to SlopeState.Ready(key, api.terrainAnalysis(polygon, null))) }
            } catch (e: CancellationException) {
                _slopes.update { it - diagram.id }
                throw e
            } catch (e: ApiException) {
                _slopes.update { it + (diagram.id to SlopeState.Unavailable(slopeWords(e))) }
            }
        }
    }

    /** Which boundary a measured slope belongs to: a raster is drawn only while the diagram's boundary is still the one it was measured for. Null with no boundary. */
    public fun boundaryKey(diagram: Diagram): String? = DiagramGeometry.boundary(diagram).takeIf { it.isNotEmpty() }?.let(::keyOf)

    private suspend fun measure(diagramId: String, boundary: JsonElement?) {
        val polygon = DiagramGeometry.polygon(boundary).takeIf { it.isNotEmpty() } ?: return
        _slopes.update { it + (diagramId to SlopeState.Loading) }
        try {
            _slopes.update { it + (diagramId to SlopeState.Ready(keyOf(polygon), api.terrainAnalysis(polygon, null))) }
        } catch (e: CancellationException) {
            _slopes.update { it - diagramId }
            throw e
        } catch (e: ApiException) {
            _slopes.update { it + (diagramId to SlopeState.Unavailable(slopeWords(e))) }
        }
    }

    private suspend fun load(id: String): Diagram? = session.document(id)

    private fun fail(diagramId: String, message: String) {
        _status.value = AnalysisStatus.Failed(diagramId, message)
    }

    /**
     * What an analysis writes into a diagram, as the web's `performTerrainAnalysis` makes it. A boundary the person drew (three points or
     * more) becomes the analysis boundary and the drawn one is cleared; otherwise the area the model found is the boundary.
     */
    private fun patch(diagram: Diagram, target: DiagramTarget, field: FieldAnalysis): JsonObject {
        val drawn = diagram.analysis.customLZ as? JsonArray
        val boundary: JsonElement = if (drawn != null && drawn.size > 2) drawn else JsonArray(field.suggestedLz.map { point -> JsonArray(point.map { JsonPrimitive(it) }) })
        return JsonObject(
            mapOf(
                "latLong" to JsonPrimitive(CoordinateParser.formatLatLongDms(target.lat, target.lon)),
                "gridElevation" to JsonPrimitive(field.elevation),
                "customLZ" to JsonNull,
                "detectedLZ" to boundary,
                "results" to ApiJson.encodeToJsonElement(FieldAnalysis.serializer(), field),
            ),
        )
    }

    private fun keyOf(polygon: List<LatLon>): String = polygon.joinToString(";") { "${it.lat},${it.lon}" }

    private fun words(e: ApiException): String = when {
        e is NetworkException -> "There is no connection to the server. Analysis needs one; the diagram is safe on this device."
        e is SessionEndedException -> "Sign in again to analyse."
        e is RateLimitedException -> "Too many requests. Wait a moment and try again."
        e.status == 400 -> "No distinct landing area was found at this point. Try a different target."
        else -> "The analysis service did not answer. Try again in a moment."
    }

    private fun slopeWords(e: ApiException): String = when {
        e is NetworkException -> "The slope could not be measured: no connection."
        e.status == 502 -> "No terrain data covers this landing zone."
        else -> "The slope could not be measured."
    }
}
