package app.ezpztac.data

import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The corners of the open diagram's boundary: which one the person is holding, and taking one away.
 *
 * Deleting is **one step of the diagram's own undo**, and a boundary keeps at least [MIN_CORNERS] (a polygon with two corners is a line). It works on whichever ring the corner is
 * in: the boundary being drawn and not yet analysed, or the analysis boundary, whose change makes the app measure the slope again by itself (the heat map is only drawn for the
 * boundary it was measured for). The corner held is forgotten when it is deleted, since the ones after it have moved up.
 */
@Singleton
class BoundaryCorners @Inject constructor(
    private val session: DiagramSession,
) {
    private val _held = MutableStateFlow<BoundaryCornerRef?>(null)

    /** The corner being held, if one is. */
    val held: StateFlow<BoundaryCornerRef?> = _held.asStateFlow()

    fun select(corner: BoundaryCornerRef) {
        _held.value = corner
    }

    fun clear() {
        _held.value = null
    }

    /** How many corners the ring [corner] is in has, for saying "corner 3 of 6"; 0 when there is none. */
    fun cornersIn(drawn: Boolean): Int {
        val diagram = session.active.value ?: return 0
        return DiagramGeometry.polygon(if (drawn) diagram.analysis.customLZ else diagram.analysis.detectedLZ).size
    }

    /** Whether the held corner can be deleted: it is there, and the ring would still have [MIN_CORNERS] without it. */
    fun canDelete(corner: BoundaryCornerRef): Boolean = cornersIn(corner.drawn) > MIN_CORNERS && corner.index in 0 until cornersIn(corner.drawn)

    /** Deletes the held corner. Null when it is gone; else why not, in words. */
    fun deleteHeld(): String? {
        val corner = _held.value ?: return "No corner is held."
        val diagram = session.active.value ?: return "Open a diagram first."
        val saved = if (corner.drawn) diagram.analysis.customLZ else diagram.analysis.detectedLZ
        val points = DiagramGeometry.polygon(saved)
        if (corner.index !in points.indices) {
            _held.value = null
            return "That corner is no longer there."
        }
        if (points.size <= MIN_CORNERS) return "A boundary needs at least $MIN_CORNERS corners."
        val ring = JsonArray(points.filterIndexed { i, _ -> i != corner.index }.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })
        val key = if (corner.drawn) "customLZ" else "detectedLZ"
        session.edit("Delete boundary corner") { d -> DiagramOps.setAnalysisDraft(d, JsonObject(mapOf(key to ring))) }
        _held.value = null
        return null
    }

    companion object {
        const val MIN_CORNERS = 3
    }
}
