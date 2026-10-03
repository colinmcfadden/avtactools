package app.ezpztac.data

import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** A boundary being drawn on [diagramId]: the points put down so far, in order. */
data class BoundaryDraft(val diagramId: String, val points: List<LatLon>) {
    val canFinish: Boolean get() = points.size >= BoundaryDrawing.MIN_POINTS
}

/**
 * Drawing a landing-zone boundary by hand, which the web does with its "Draw LZ/PZ boundary" button (`toggleDrawingMode`): the person
 * puts down the corners one by one, and the finished ring is saved as the diagram's `analysis.customLZ`. The next analysis takes it as the
 * boundary instead of the one the model finds (`AnalysisService`).
 *
 * The points are a draft held here, never in the diagram, until the person finishes: abandoning a half-drawn boundary leaves no trace. The
 * draft belongs to one diagram and is dropped when another is opened ([cancel]).
 *
 * As on the web, starting to draw throws away what is there: an earlier drawn boundary, and the analysis of a diagram that was analysed
 * (its graphics stay: the aircraft are still where they were put, but they need an analysis again before they can be edited). Unlike the
 * web, that is one step of the diagram's own undo, so it can be taken back.
 */
@Singleton
class BoundaryDrawing @Inject constructor(private val session: DiagramSession) {
    private val _draft = MutableStateFlow<BoundaryDraft?>(null)

    /** The boundary being drawn, or null when nothing is. */
    val draft: StateFlow<BoundaryDraft?> = _draft.asStateFlow()

    /**
     * Starts a draft on the open diagram, clearing its drawn boundary and, if it was analysed, its analysis. Null when drawing has started, or why not
     * in words.
     */
    fun start(): String? {
        val open = session.active.value ?: return "Open a diagram first."
        if (open.target == null) return "Set a target before drawing a boundary."
        if (_draft.value?.diagramId == open.id) return null                           // already drawing here: the points so far stay
        session.edit("Draw boundary") { d ->
            val cleared = if (d.status == DiagramStatus.ANALYZED) DiagramOps.resetAnalysis(d) else d
            DiagramOps.setAnalysisDraft(cleared, JsonObject(mapOf("customLZ" to JsonNull)))
        }
        _draft.value = BoundaryDraft(open.id, emptyList())
        return null
    }

    /** Puts a corner down at [at]. False when nothing is being drawn on the open diagram, or [at] is not a position. */
    fun addPoint(at: LatLon): Boolean {
        val current = _draft.value ?: return false
        if (session.active.value?.id != current.diagramId) return false
        if (at.lat !in -90.0..90.0 || at.lon !in -180.0..180.0) return false              // NaN is in no range, so it is refused here too
        _draft.value = current.copy(points = current.points + at)
        return true
    }

    /** Takes the last corner back. */
    fun removeLastPoint() {
        val current = _draft.value ?: return
        _draft.value = current.copy(points = current.points.dropLast(1))
    }

    /** Stops drawing and forgets the points. The diagram is as [start] left it. */
    fun cancel() {
        _draft.value = null
    }

    /**
     * Saves the draft as the diagram's drawn boundary and stops drawing. Null when it is saved; else why not, and drawing goes on (a boundary needs
     * [MIN_POINTS] corners).
     */
    fun finish(): String? {
        val current = _draft.value ?: return "Nothing is being drawn."
        if (!current.canFinish) return "A boundary needs at least $MIN_POINTS points."
        if (session.active.value?.id != current.diagramId) {
            _draft.value = null
            return "The diagram this was drawn on is no longer open."
        }
        val ring = JsonArray(current.points.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })
        session.edit("Draw boundary") { d -> DiagramOps.setAnalysisDraft(d, JsonObject(mapOf("customLZ" to ring))) }
        _draft.value = null
        return null
    }

    companion object {
        /** The web finishes a boundary only with more than two points. */
        const val MIN_POINTS = 3
    }
}
