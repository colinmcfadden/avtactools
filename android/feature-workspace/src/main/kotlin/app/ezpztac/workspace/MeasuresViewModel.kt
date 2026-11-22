package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.ControlMeasures
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.HeldMeasure
import app.ezpztac.data.MeasureKind
import app.ezpztac.data.OpenPack
import app.ezpztac.data.PackLock
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.Backstops
import app.ezpztac.planning.PhaseLines
import app.ezpztac.planning.SetLines
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A backstop, FLOT or phase line being drawn, as its bar over the map shows it: what, how many points are down, and whether it can be finished. */
data class MeasureDraftUi(val kind: MeasureKind, val points: Int, val canFinish: Boolean)

/**
 * A backstop, FLOT or phase line held on the map, as the bar over the map shows it: [title] ("Backstop", "FLOT", "PL RED"), [subtitle] (a backstop's azimuth and
 * length, a line's set), a phase line's [name] to type over, and why it cannot be changed ([locked], a read-only pack's). [key] tells one from another, so what is
 * typed for one is never shown for the next.
 */
data class MeasureHeldUi(
    val kind: MeasureKind,
    val key: String,
    val title: String,
    val subtitle: String?,
    val name: String? = null,
    val locked: String? = null,
)

data class MeasuresUiState(
    val draft: MeasureDraftUi? = null,
    val held: MeasureHeldUi? = null,
    /** The name offered while the phase line's name is asked for (the web's name dialog); null while it is not. */
    val naming: String? = null,
    /** Why the last thing asked was not done, in words; cleared by the next. */
    val error: String? = null,
)

/**
 * The control measures' screens (the bar over the map while one is drawn, the held bar's part, the name a phase line is finished under, the tools that start one), over
 * [ControlMeasures], which does all of it: this only says it in words and keeps what the person was asked.
 */
@HiltViewModel
class MeasuresViewModel @Inject constructor(
    private val measures: ControlMeasures,
    private val diagrams: DiagramSession,
    private val pack: OpenPack = OpenPack.None,
) : ViewModel() {
    private val naming = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    private val held = combine(measures.held, diagrams.active, measures.sets, pack.lock) { held, diagram, sets, lock ->
        heldUi(held, diagram, sets, lock)
    }

    val state: StateFlow<MeasuresUiState> = combine(measures.draft, held, naming, error) { draft, held, naming, error ->
        MeasuresUiState(draft?.let { MeasureDraftUi(it.kind, it.points.size, it.canFinish) }, held, naming.takeIf { draft?.kind == MeasureKind.PHASE_LINE }, error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeasuresUiState())

    /** Why [kind] cannot be drawn now, in words, or null when it can (a tool's button says so as it is greyed out). */
    fun refusal(kind: MeasureKind): String? = measures.refusal(kind)

    /** Starts drawing [kind]; null once it has started, else why not, in words (also kept as [MeasuresUiState.error] for the bar). */
    fun start(kind: MeasureKind): String? {
        val refused = measures.start(kind)
        error.value = refused
        return refused
    }

    /** Starts drawing [kind] with its first point at [at] (the map's long-press menu): null once it has, else why not. */
    fun startAt(kind: MeasureKind, at: LatLon): String? {
        start(kind)?.let { return it }
        measures.addPoint(at)
        return null
    }

    /** The tool's button pressed: starts [kind], or, while it is being drawn, finishes it once it can be and cancels it while it cannot (the web's toggles). */
    fun toggle(kind: MeasureKind): String? {
        val draft = measures.draft.value
        if (draft?.kind != kind) return start(kind)
        if (draft.canFinish) finish() else cancel()
        return null
    }

    /** A point at the crosshair (*Add here*): the precise way, with the sheet down. */
    fun addAt(crosshair: LatLon?) {
        if (crosshair == null) {
            error.value = MOVE_MAP_FIRST
            return
        }
        error.value = if (measures.addPoint(crosshair)) null else "That point could not be added."
    }

    fun undoPoint() {
        error.value = null
        measures.undoPoint()
    }

    fun cancel() {
        error.value = null
        naming.value = null
        measures.cancel()
    }

    /** *Finish*: a phase line is first named (the web asks for its name); a backstop or a FLOT is kept at once. */
    fun finish() {
        val draft = measures.draft.value ?: return
        if (!draft.canFinish) {
            error.value = ControlMeasures.ADD_TWO
            return
        }
        if (draft.kind == MeasureKind.PHASE_LINE) {
            naming.value = measures.nextPhaseLineName()
            return
        }
        keep(null)
    }

    /** The phase line's name given: kept under it, or the name offered when nothing usable was typed (the web's `cleanPhaseLineName(name) || suggested`). */
    fun confirmName(name: String) {
        val offered = naming.value ?: return
        naming.value = null
        keep(name.takeIf { PhaseLines.cleanName(it).isNotEmpty() } ?: offered)
    }

    /** The name's dialog put away: the line goes on being drawn, as on the web. */
    fun cancelNaming() {
        naming.value = null
    }

    /** Deletes what is held; null once it is, else why not (said under the bar's buttons). */
    fun delete(): String? = measures.deleteHeld()

    /** Turns the held FLOT's scallops to its other side. */
    fun flip(): String? = measures.flipHeld()

    /** Gives the held phase line another name. */
    fun rename(name: String): String? = measures.renameHeld(name)

    fun dismissError() {
        error.value = null
    }

    private fun keep(name: String?) {
        viewModelScope.launch { error.value = measures.finish(name) }
    }

    private fun heldUi(held: HeldMeasure?, diagram: app.ezpztac.model.Diagram?, sets: List<RouteSet>, lock: PackLock?): MeasureHeldUi? = when (held) {
        null -> null
        is HeldMeasure.Backstop -> diagram?.takeIf { it.id == held.diagramId }?.let { d ->
            DiagramOps.graphic(d, ControlMeasures.BACKSTOPS, held.id)?.let { backstop ->
                val target = d.target?.let { LatLon(it.lat, it.lon) }
                val reading = Backstops.arrow(backstop, target)?.let { Backstops.reading(it).text }
                MeasureHeldUi(
                    MeasureKind.BACKSTOP, "${d.id}/${held.id}", "Backstop", listOfNotNull(reading, d.name.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null },
                    locked = lock?.reasonFor(d.id),
                )
            }
        }
        is HeldMeasure.Line -> sets.firstOrNull { it.id == held.setId }?.let { set ->
            SetLines.of(set, held.key).firstOrNull { DiagramOps.idText(it["id"]) == held.id }?.let { line ->
                val flot = held.key == SetLines.FLOTS
                MeasureHeldUi(
                    if (flot) MeasureKind.FLOT else MeasureKind.PHASE_LINE, "${set.id}/${held.key}/${held.id}",
                    if (flot) "FLOT" else PhaseLines.label(line), set.name.takeIf { it.isNotBlank() } ?: SetLines.MEASURES_NAME,
                    name = if (flot) null else PhaseLines.name(line), locked = lock?.reasonFor(set.id),
                )
            }
        }
    }

    companion object {
        const val MOVE_MAP_FIRST = "Move the map to where the point should go first."

        /** The longest name a phase line takes (the web's `PHASE_LINE_NAME_MAX`). */
        const val NAME_MAX = PhaseLines.NAME_MAX
    }
}
