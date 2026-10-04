package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.BoundaryCorners
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.data.DiagramSession
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.LatLon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class BoundaryUiState(
    /** A boundary is being drawn. */
    val drawing: Boolean = false,
    /** The corners put down so far. */
    val points: Int = 0,
    /** Enough corners to finish. */
    val canFinish: Boolean = false,
    /** A diagram with a target is open and nothing is in the way of drawing on it. */
    val canStart: Boolean = false,
    /** Why drawing cannot start, when a diagram is open that it cannot start on. */
    val cannotStart: String? = null,
    /** Starting would throw away an analysis or an earlier drawn boundary, so the person is asked first. */
    val clears: Boolean = false,
    /** The corners of the boundary the open diagram already has drawn (not analysed yet), or 0. */
    val drawnPoints: Int = 0,
    val error: String? = null,
    /** The corner of the boundary being held on the map, if one is. */
    val heldCorner: HeldCornerUi? = null,
)

/** A corner of the boundary held on the map: what it is (`Boundary corner 3 of 6`), where, and whether it can be deleted (a boundary keeps three). */
data class HeldCornerUi(val title: String, val grid: String, val canDelete: Boolean)

/**
 * Drawing a landing-zone boundary by hand (the web's "Draw LZ/PZ boundary"): the sheet's start button, and the toolbar over the map that
 * adds corners, takes one back, finishes and cancels. The points and the rules are [BoundaryDrawing]'s; this is what a screen shows of them.
 */
@HiltViewModel
class BoundaryViewModel @Inject constructor(
    private val drawing: BoundaryDrawing,
    session: DiagramSession,
    analysis: AnalysisService,
    private val corners: BoundaryCorners,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<BoundaryUiState> = combine(drawing.draft, session.active, analysis.status, error, corners.held) { draft, diagram, status, error, heldCorner ->
        val running = status is AnalysisStatus.Running && status.diagramId == diagram?.id
        val startable = diagram != null && diagram.target != null
        BoundaryUiState(
            drawing = draft != null,
            points = draft?.points?.size ?: 0,
            canFinish = draft?.canFinish == true,
            canStart = draft == null && startable && !running,
            cannotStart = when {
                draft != null || diagram == null -> null
                diagram.target == null -> "Set a target before drawing a boundary."
                running -> "Wait for the analysis to finish, or stop it, before drawing a boundary."
                else -> null
            },
            clears = diagram != null && (diagram.status == DiagramStatus.ANALYZED || DiagramGeometry.drawn(diagram).isNotEmpty()),
            drawnPoints = diagram?.let { DiagramGeometry.drawn(it).size } ?: 0,
            error = error,
            heldCorner = diagram?.let { d -> heldCorner?.let { cornerOf(d, it) } },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BoundaryUiState())

    /** The held corner as the bar shows it, or null when it is not there any more. */
    private fun cornerOf(diagram: app.ezpztac.model.Diagram, corner: BoundaryCornerRef): HeldCornerUi? {
        val ring = DiagramGeometry.polygon(if (corner.drawn) diagram.analysis.customLZ else diagram.analysis.detectedLZ)
        val at = ring.getOrNull(corner.index) ?: return null
        val grid = MgrsConverter.toMgrs(at.lat, at.lon)?.format() ?: "%.5f, %.5f".format(java.util.Locale.ROOT, at.lat, at.lon)
        return HeldCornerUi("Boundary corner ${corner.index + 1} of ${ring.size}", grid, canDelete = ring.size > BoundaryCorners.MIN_CORNERS)
    }

    /** Deletes the corner held on the map (one undo step). Its refusal, if any, is in words in [BoundaryUiState.error]. */
    fun deleteCorner() = fail(corners.deleteHeld())

    fun start() = fail(drawing.start())

    /** Puts a corner down at the crosshair, [at]. */
    fun addAtCrosshair(at: LatLon?) {
        if (at == null) return fail("Move the map to where the corner should go first.")
        if (!drawing.addPoint(at)) fail("That corner could not be added.") else error.value = null
    }

    fun undoPoint() {
        error.value = null
        drawing.removeLastPoint()
    }

    fun finish() = fail(drawing.finish())

    fun cancel() {
        error.value = null
        drawing.cancel()
    }

    fun dismissError() {
        error.value = null
    }

    private fun fail(message: String?) = error.update { message }
}
