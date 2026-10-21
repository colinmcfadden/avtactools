package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.AnalysisService
import app.ezpztac.data.AnalysisStatus
import app.ezpztac.data.BoundaryCorners
import app.ezpztac.data.BoundaryDrawing
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.OpenPack
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.DiagramGeometry
import app.ezpztac.model.DiagramStatus
import app.ezpztac.model.LatLon
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** Why the open LZ/PZ's boundary cannot be changed: it is a read-only pack's. Its tools say it once, so [cannotStart] does not. */
    val locked: String? = null,
    /** A boundary was just finished on the open LZ/PZ: the bar over the map offers its analysis there and then, until it is analysed, dismissed or drawn again. */
    val analyzeOffer: AnalyzeOfferUi? = null,
)

/**
 * The bar that takes the drawing toolbar's place once *Finish* is pressed (the owner's request, 2026-10-21: no going into the panel and its plan to analyse a
 * boundary just drawn): the corners drawn, and the analysis offered, under way ([stage], in words) or failed ([failed], in words).
 */
data class AnalyzeOfferUi(val points: Int, val stage: String? = null, val failed: String? = null)

/**
 * A corner of the boundary held on the map: what it is (`Boundary corner 3 of 6`), where, and whether it can be deleted (a boundary keeps three), or why
 * nothing of it can be ([locked]: a read-only pack's).
 */
data class HeldCornerUi(val title: String, val grid: String, val canDelete: Boolean, val locked: String? = null)

/**
 * Drawing a landing-zone boundary by hand (the web's "Draw LZ/PZ boundary"): the sheet's start button, and the toolbar over the map that
 * adds corners, takes one back, finishes and cancels. The points and the rules are [BoundaryDrawing]'s; this is what a screen shows of them.
 */
@HiltViewModel
class BoundaryViewModel @Inject constructor(
    private val drawing: BoundaryDrawing,
    private val session: DiagramSession,
    private val analysis: AnalysisService,
    private val corners: BoundaryCorners,
    /** The open Mission Pack: a read-only one's boundary is looked at, never drawn again or cut (P2c). */
    private val pack: OpenPack = OpenPack.None,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    /**
     * The boundary finished last: on which diagram, with how many corners (said once the analysis has taken the drawn boundary as its own, when the drawn one is cleared), and
     * whether an analysis of that diagram has run since ([ran]), after which the offer is spent: a later analysis there (*Re-analyze* in the panel) is not this boundary's.
     */
    private data class Finished(val diagramId: String, val points: Int, val ran: Boolean = false)

    private val finished = MutableStateFlow<Finished?>(null)

    private val held = combine(corners.held, pack.lock, finished) { corner, lock, finished -> Triple(corner, lock, finished) }

    val state: StateFlow<BoundaryUiState> = combine(drawing.draft, session.active, analysis.status, error, held) { draft, diagram, status, error, held ->
        val (heldCorner, lock, finished) = held
        val locked = lock?.reasonFor(diagram?.id)
        val running = status is AnalysisStatus.Running && status.diagramId == diagram?.id
        val startable = diagram != null && diagram.target != null
        BoundaryUiState(
            drawing = draft != null,
            points = draft?.points?.size ?: 0,
            canFinish = draft?.canFinish == true && locked == null,
            canStart = draft == null && startable && !running && locked == null,
            locked = locked,
            cannotStart = when {
                draft != null || diagram == null || locked != null -> null
                diagram.target == null -> "Set a target before drawing a boundary."
                running -> "Wait for the analysis to finish, or stop it, before drawing a boundary."
                else -> null
            },
            clears = diagram != null && (diagram.status == DiagramStatus.ANALYZED || DiagramGeometry.drawn(diagram).isNotEmpty()),
            drawnPoints = diagram?.let { DiagramGeometry.drawn(it).size } ?: 0,
            error = error,
            heldCorner = diagram?.let { d -> heldCorner?.let { cornerOf(d, it)?.copy(locked = locked) } },
            analyzeOffer = offerOf(finished, diagram, status).takeIf { draft == null && locked == null },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BoundaryUiState())

    init {
        // An analysis of the boundary's diagram is noted when it is seen running; once one has run and is over with the drawn boundary taken, the offer is spent.
        viewModelScope.launch {
            combine(finished, session.active, analysis.status) { f, d, s -> Triple(f, d, s) }.collect { (f, diagram, status) ->
                if (f == null || diagram == null || f.diagramId != diagram.id) return@collect
                val running = (status as? AnalysisStatus.Running)?.diagramId == diagram.id
                val failed = (status as? AnalysisStatus.Failed)?.diagramId == diagram.id
                when {
                    running && !f.ran -> finished.value = f.copy(ran = true)
                    f.ran && !running && !failed && DiagramGeometry.drawn(diagram).isEmpty() -> finished.value = null
                }
            }
        }
    }

    /**
     * The offer for the boundary just finished, while it stands: its diagram is the one open, and the boundary waits for an analysis or one of it is under way or failed.
     * An analysis that succeeds takes the drawn boundary as its own and clears it, so the offer goes once it is over.
     */
    private fun offerOf(finished: Finished?, diagram: app.ezpztac.model.Diagram?, status: AnalysisStatus): AnalyzeOfferUi? {
        if (finished == null || diagram == null || finished.diagramId != diagram.id) return null
        val running = (status as? AnalysisStatus.Running)?.takeIf { it.diagramId == diagram.id }
        val failed = (status as? AnalysisStatus.Failed)?.takeIf { it.diagramId == diagram.id }
        val drawn = DiagramGeometry.drawn(diagram).size
        if (running == null && failed == null && drawn == 0) return null
        return AnalyzeOfferUi(
            points = if (drawn > 0) drawn else finished.points,                                  // a corner deleted since is counted
            stage = running?.let { if (it.stage == AnalysisStatus.Running.Stage.FINDING_AREA) "Finding the landing area…" else "Measuring the slope…" },
            failed = failed?.message,
        )
    }

    /** The held corner as the bar shows it, or null when it is not there any more. */
    private fun cornerOf(diagram: app.ezpztac.model.Diagram, corner: BoundaryCornerRef): HeldCornerUi? {
        val ring = DiagramGeometry.polygon(if (corner.drawn) diagram.analysis.customLZ else diagram.analysis.detectedLZ)
        val at = ring.getOrNull(corner.index) ?: return null
        val grid = MgrsConverter.toMgrs(at.lat, at.lon)?.format() ?: "%.5f, %.5f".format(java.util.Locale.ROOT, at.lat, at.lon)
        return HeldCornerUi("Boundary corner ${corner.index + 1} of ${ring.size}", grid, canDelete = ring.size > BoundaryCorners.MIN_CORNERS)
    }

    // Why the open LZ/PZ cannot be changed now: it is a read-only pack's. Asked before anything is changed, so nothing is changed only to be put back by the pack.
    private fun locked(): String? = pack.lockNow()?.reasonFor(session.active.value?.id)

    /** Deletes the corner held on the map (one undo step). Its refusal, if any, is in words in [BoundaryUiState.error]. */
    fun deleteCorner() = fail(locked() ?: corners.deleteHeld())

    /** Starts drawing the open LZ/PZ's boundary (which clears the one there: a change, so never in a read-only pack). A boundary drawn again is offered again when it is finished. */
    fun start() {
        val open = session.active.value?.id
        val problem = locked() ?: drawing.start()
        fail(problem)
        // This LZ/PZ's boundary drawn again: its offer comes with the new one. Another LZ/PZ's waits until a boundary is finished here, which takes its place.
        if (problem == null && finished.value?.diagramId == open) finished.value = null
    }

    /** Puts a corner down at the crosshair, [at]. */
    fun addAtCrosshair(at: LatLon?) {
        if (at == null) return fail("Move the map to where the corner should go first.")
        if (!drawing.addPoint(at)) fail("That corner could not be added.") else error.value = null
    }

    fun undoPoint() {
        error.value = null
        drawing.removeLastPoint()
    }

    /** Finishes the boundary; once it is the open LZ/PZ's, the bar over the map offers to analyse it ([BoundaryUiState.analyzeOffer]). */
    fun finish() {
        val draft = drawing.draft.value
        val problem = locked() ?: drawing.finish()
        fail(problem)
        if (problem == null && draft != null) {
            // A failure of an earlier analysis of this LZ/PZ was not this boundary's: the offer starts from nothing tried.
            if ((analysis.status.value as? AnalysisStatus.Failed)?.diagramId == draft.diagramId) analysis.dismiss()
            finished.value = Finished(draft.diagramId, draft.points.size)
        }
    }

    /** Analyses the open LZ/PZ from the bar over the map, as the panel's *Analyze* does: never in a read-only pack, whose result would be put back. */
    fun analyzeDrawn() {
        val open = session.active.value ?: return
        locked()?.let { return fail(it) }
        // One analysis at a time: another LZ/PZ's under way would make this press do nothing at all.
        val running = analysis.status.value as? AnalysisStatus.Running
        if (running != null && running.diagramId != open.id) return fail("Another LZ/PZ is being analysed. Wait for it to finish, or stop it in its panel.")
        error.value = null
        analysis.analyze(open.id)
    }

    /** Stops waiting for the server; the boundary is still drawn, and offered again. */
    fun stopAnalysis() = analysis.cancel()

    /** *Not now*: the bar goes, and the boundary waits in the panel, where *Analyze* is. */
    fun dismissOffer() {
        finished.value = null
    }

    fun cancel() {
        error.value = null
        drawing.cancel()
    }

    fun dismissError() {
        error.value = null
    }

    private fun fail(message: String?) = error.update { message }
}
