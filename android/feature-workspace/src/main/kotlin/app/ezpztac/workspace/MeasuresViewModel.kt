package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.ControlMeasures
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.HeldMeasure
import app.ezpztac.data.MeasureKind
import app.ezpztac.data.OpenPack
import app.ezpztac.data.PackLock
import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.JsNumber
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

/** A FLOT or phase line's row on the Routes panel's card (the web's `FlotRow`, `PhaseLineRow`): its title, what it is and how long, and whether it is held. */
data class MeasureLineUi(val line: HeldMeasure.Line, val title: String, val meta: String, val held: Boolean, val name: String? = null) {
    val isFlot: Boolean get() = line.key == SetLines.FLOTS
}

/**
 * A card of the Routes panel's *Control measures* (the web's `MeasuresCard`): a set of them ([attached] false: renamed and closed here), or the lines an older web saved
 * inside route set [name] ([attached]: moved out by *Keep on their own*). [inPack] says the set is the open pack's (*Close here*); [locked] why nothing in it may change.
 */
data class MeasuresCardUi(
    val setId: String,
    val name: String,
    val attached: Boolean,
    val summary: String,
    val lines: List<MeasureLineUi>,
    val inPack: Boolean = false,
    val locked: String? = null,
)

data class MeasuresUiState(
    val draft: MeasureDraftUi? = null,
    val held: MeasureHeldUi? = null,
    /** The Routes panel's *Control measures*: each set with lines in it, a set of them first (the web's `measureSets`). */
    val cards: List<MeasuresCardUi> = emptyList(),
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

    private val cards = combine(measures.sets, measures.held, pack.lock) { sets, held, lock -> cardsOf(sets, held, lock) }

    val state: StateFlow<MeasuresUiState> = combine(measures.draft, held, cards, naming, error) { draft, held, cards, naming, error ->
        MeasuresUiState(draft?.let { MeasureDraftUi(it.kind, it.points.size, it.canFinish) }, held, cards, naming.takeIf { draft?.kind == MeasureKind.PHASE_LINE }, error)
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

    /** A row of the card tapped: the line held on the map, or put down if it was (the map's own tap does the same). */
    fun holdLine(line: HeldMeasure.Line) {
        measures.toggle(line)
    }

    /** A row's *Remove* (the web's `removeFlot`, `removePhaseLine`, which ask nothing: undo takes it back). */
    fun removeLine(line: HeldMeasure.Line): String? = measures.deleteLine(line).also { error.value = it }

    /** A FLOT row's *Flip*. */
    fun flipLine(line: HeldMeasure.Line): String? = measures.flipLine(line).also { error.value = it }

    /** A phase line row's rename. */
    fun renameLine(line: HeldMeasure.Line, name: String): String? = measures.renameLine(line, name).also { error.value = it }

    /** The card's rename of a set of control measures. */
    fun renameSet(name: String): String? = measures.renameSet(name).also { error.value = it }

    /** The card's *Close* (*Close here* in a pack): the set's last change written, then it is out of this session (the Library or the pack keeps it). */
    fun closeSet() {
        viewModelScope.launch { measures.closeSet() }
    }

    /** *Keep on their own*: the lines saved inside route set [setId] moved into the control measures. */
    fun keepOnTheirOwn(setId: String) {
        viewModelScope.launch { error.value = measures.keepOnTheirOwn(setId) }
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

    private fun cardsOf(sets: List<RouteSet>, held: HeldMeasure?, lock: PackLock?): List<MeasuresCardUi> =
        sets.filter { SetLines.count(it) > 0 }
            .sortedBy { if (isMeasures(it)) 0 else 1 }
            .map { set ->
                val flots = SetLines.of(set, SetLines.FLOTS)
                val phaseLines = SetLines.of(set, SetLines.PHASE_LINES)
                val rows = flots.map { flot ->
                    val line = HeldMeasure.Line(set.id, SetLines.FLOTS, DiagramOps.idText(flot["id"]))
                    MeasureLineUi(line, "FLOT", "${km(flot)} km", held == line)
                } + phaseLines.map { pl ->
                    val line = HeldMeasure.Line(set.id, SetLines.PHASE_LINES, DiagramOps.idText(pl["id"]))
                    MeasureLineUi(line, PhaseLines.label(pl), "Phase line · ${km(pl)} km", held == line, name = PhaseLines.name(pl))
                }
                MeasuresCardUi(
                    set.id, set.name.ifBlank { SetLines.MEASURES_NAME }, attached = !isMeasures(set), summary = summaryOf(flots.size, phaseLines.size), lines = rows,
                    inPack = PackRef.parse(set.id) != null, locked = lock?.reasonFor(set.id),
                )
            }

    private fun isMeasures(set: RouteSet) = SetLines.isMeasures(set, set.name == SetLines.MEASURES_NAME)

    companion object {
        /** What a card holds, as the web's `setSummary` says it with no routes: `1 FLOT · 2 phase lines`. */
        internal fun summaryOf(flots: Int, phaseLines: Int): String = listOfNotNull(
            flots.takeIf { it > 0 }?.let { if (it == 1) "1 FLOT" else "$it FLOTs" },
            phaseLines.takeIf { it > 0 }?.let { if (it == 1) "1 phase line" else "$it phase lines" },
        ).joinToString(" · ")

        // A line's length to a tenth of a km, as the web's `toFixed(1)`.
        private fun km(line: kotlinx.serialization.json.JsonObject): String = JsNumber.toFixed(SetLines.lengthKm(line), 1)

        const val MOVE_MAP_FIRST = "Move the map to where the point should go first."

        /** The longest name a phase line takes (the web's `PHASE_LINE_NAME_MAX`). */
        const val NAME_MAX = PhaseLines.NAME_MAX
    }
}
