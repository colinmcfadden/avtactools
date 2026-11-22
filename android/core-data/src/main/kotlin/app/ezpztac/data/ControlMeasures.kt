package app.ezpztac.data

import app.ezpztac.missionpacks.PackRef
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.Backstops
import app.ezpztac.planning.Flots
import app.ezpztac.planning.PhaseLines
import app.ezpztac.planning.SetLines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** The three lines drawn across the map point by point: a backstop (an LZ/PZ's graphic), a FLOT and a phase line (control measures). */
enum class MeasureKind(val drawing: DrawingMode.Kind, val noun: String) {
    BACKSTOP(DrawingMode.Kind.BACKSTOP, "backstop"),
    FLOT(DrawingMode.Kind.FLOT, "FLOT"),
    PHASE_LINE(DrawingMode.Kind.PHASE_LINE, "phase line"),
}

/** A line being drawn: its points so far, in order, and for a backstop the LZ/PZ it is drawn for. */
data class MeasureDraft(val kind: MeasureKind, val points: List<LatLon>, val diagramId: String? = null) {
    val canFinish: Boolean get() = points.size >= ControlMeasures.MIN_POINTS
}

/** What the person holds of these: a backstop of an LZ/PZ, or a FLOT or phase line ([key]: `flots` or `phaseLines`) of an open set. */
sealed interface HeldMeasure {
    data class Backstop(val diagramId: String, val id: String) : HeldMeasure

    data class Line(val setId: String, val key: String, val id: String) : HeldMeasure
}

/**
 * Backstops, FLOTs and phase lines (the web's feature/backstops, feature/flot, feature/phaseLines and feature/setLines, as App.jsx drives them): drawing one point by
 * point, holding one, and the edits a person makes to one. What each is, and its arithmetic, is core-planning's ([Backstops], [Flots], [PhaseLines], [SetLines]),
 * held to the web's fixtures; what is here is where each is kept and changed.
 *
 * - **A backstop is a graphic of the open LZ/PZ** (`graphics.backstops`), made only once it is analysed, and edited in the diagram's session like any graphic.
 * - **A FLOT or a phase line is a control measure**, kept in a set of its own, not with a route set (AGENTS.md §4, *Control measures*): a new one goes into the set
 *   of them open in [measures], else the route session's set when that is one of them (a saved set of control measures opened as routes), else one made for it:
 *   in a mission pack the pack's (`packMeasuresFor`: [OpenPack.openMeasures]), outside one a record of the person's Library called [SetLines.MEASURES_NAME],
 *   made with the line in it. Lines saved inside a route set before that (an older web's) are drawn and edited where they are.
 *
 * One drawing at a time ([DrawingMode]); in a read-only pack nothing is drawn or changed, and why is said ([PackLock]).
 */
@Singleton
class ControlMeasures @Inject constructor(
    private val diagrams: DiagramSession,
    private val routes: RouteSession,
    private val measures: MeasuresSession,
    private val repository: RouteRepository,
    private val pack: OpenPack,
    private val mode: DrawingMode,
    private val last: LastMeasures,
) {
    private val _draft = MutableStateFlow<MeasureDraft?>(null)
    private val _held = MutableStateFlow<HeldMeasure?>(null)

    /** The line being drawn, or null when none is. */
    val draft: StateFlow<MeasureDraft?> = _draft.asStateFlow()

    /** What is held, or null. */
    val held: StateFlow<HeldMeasure?> = _held.asStateFlow()

    /** The open sets whose FLOTs and phase lines are drawn: the route session's (not a mission's) and the control measures', each once. */
    val sets: Flow<List<RouteSet>> = combine(routes.active, measures.active) { a, b -> setsOf(a, b) }.distinctUntilChanged()

    /** [sets] now. */
    fun setsNow(): List<RouteSet> = setsOf(routes.active.value, measures.active.value)

    /** The control measures' own set open now, if one is. */
    val measuresSet: StateFlow<RouteSet?> get() = measures.active

    // -- Drawing ----------------------------------------------------------------------------------------------------

    /** Why [kind] cannot be drawn now, in words, or null when it can: [start]'s answer without starting. */
    fun refusal(kind: MeasureKind): String? {
        lockedFor(kind)?.let { return it }
        if (kind == MeasureKind.BACKSTOP) {
            val open = diagrams.active.value ?: return "Open an LZ/PZ first."
            if (open.target == null) return "Set a target first."
            if (!open.canEditGraphics) return "Analyze the LZ/PZ first."
        }
        return _draft.value?.kind?.takeIf { it != kind }?.let { "Finish or cancel the ${it.noun} first." } ?: mode.refusal(kind.drawing)
    }

    /** Starts drawing [kind]; null when it has started (or already was), else why not, in words. */
    fun start(kind: MeasureKind): String? {
        if (_draft.value?.kind == kind) return null
        refusal(kind)?.let { return it }
        mode.begin(kind.drawing)?.let { return it }
        _held.value = null
        _draft.value = MeasureDraft(kind, emptyList(), diagrams.active.value?.id.takeIf { kind == MeasureKind.BACKSTOP })
        return null
    }

    /** Puts a point down at [at]. False when nothing is being drawn (or a backstop's LZ/PZ is no longer open), or [at] is not a position. */
    fun addPoint(at: LatLon): Boolean {
        val current = _draft.value ?: return false
        if (current.kind == MeasureKind.BACKSTOP && diagrams.active.value?.id != current.diagramId) return false
        if (at.lat !in -90.0..90.0 || !at.lon.isFinite()) return false
        _draft.value = current.copy(points = current.points + at)
        return true
    }

    /** Takes the last point back. */
    fun undoPoint() {
        val current = _draft.value ?: return
        _draft.value = current.copy(points = current.points.dropLast(1))
    }

    /** Stops drawing and forgets the points. */
    fun cancel() {
        val current = _draft.value ?: return
        _draft.value = null
        mode.end(current.kind.drawing)
    }

    /** A backstop being drawn for another LZ/PZ than [diagramId] is let go of (the web cancels it as the LZ/PZ changes). */
    fun dropBackstopNotOn(diagramId: String?) {
        val current = _draft.value ?: return
        if (current.kind == MeasureKind.BACKSTOP && current.diagramId != diagramId) cancel()
    }

    /** The name a new phase line is offered (`nextPhaseLineName` of the lines on the map). */
    fun nextPhaseLineName(): String = PhaseLines.nextName(setsNow().flatMap { SetLines.of(it, SetLines.PHASE_LINES) })

    /**
     * Finishes the line being drawn: kept where it belongs, held, and drawing stops. [name] is a phase line's (the name dialog's; the offered one when null).
     * Null when it is kept; else why not, in words, and drawing goes on (a line needs [MIN_POINTS] points, a read-only pack takes nothing).
     */
    suspend fun finish(name: String? = null): String? {
        val current = _draft.value ?: return "Nothing is being drawn."
        if (!current.canFinish) return ADD_TWO
        lockedFor(current.kind)?.let { return it }
        val stamp = System.currentTimeMillis()
        if (current.kind == MeasureKind.BACKSTOP) {
            val open = diagrams.active.value
            if (open == null || open.id != current.diagramId || !open.canEditGraphics) {
                cancel()
                return "The LZ/PZ this was drawn for is no longer open."
            }
            val id = "bs-$stamp"
            diagrams.edit("Add backstop") { d ->
                DiagramOps.setGraphicItems(d, BACKSTOPS, d.graphics.backstops.orEmpty() + Backstops.create(current.points, id))
            }
            cancel()
            _held.value = HeldMeasure.Backstop(open.id, id)
            return null
        }
        val key = if (current.kind == MeasureKind.FLOT) SetLines.FLOTS else SetLines.PHASE_LINES
        val line = if (current.kind == MeasureKind.FLOT) {
            Flots.create(current.points, lineId("flot", stamp))
        } else {
            val clean = PhaseLines.cleanName(name ?: nextPhaseLineName())
            if (clean.isEmpty()) return "Give the phase line a name."
            PhaseLines.create(current.points, lineId("pl", stamp), clean)
        }
        val lineIdText = DiagramOps.idText(line["id"])
        val into = holderNow()
        if (into != null) {
            val setId = into.active.value?.id ?: return "The control measures are no longer open."
            into.edit(label(current.kind)) { set -> SetLines.withLines(set, key, SetLines.of(set, key) + line) }
            cancel()
            _held.value = HeldMeasure.Line(setId, key, lineIdText)
            return null
        }
        if (pack.state.value != null) {
            val localId = pack.openMeasures() ?: return "The pack would not take the control measures."
            if (measures.active.value?.id != localId) return "The control measures are no longer open."
            measures.edit(label(current.kind)) { set -> SetLines.withLines(set, key, SetLines.of(set, key) + line) }
            cancel()
            _held.value = HeldMeasure.Line(localId, key, lineIdText)
            return null
        }
        val made = repository.create(SetLines.MEASURES_NAME, extras = JsonObject(mapOf(key to JsonArray(listOf(line)))))
        if (!measures.open(made.id)) return "The control measures could not be opened."
        last.remember(made.id)
        cancel()
        _held.value = HeldMeasure.Line(made.id, key, lineIdText)
        return null
    }

    // -- Holding ----------------------------------------------------------------------------------------------------

    fun hold(what: HeldMeasure) {
        _held.value = what
    }

    /** Holding what is held lets go of it, so a second tap on a line puts it down. */
    fun toggle(what: HeldMeasure) {
        _held.value = if (_held.value == what) null else what
    }

    fun release() {
        _held.value = null
    }

    /** The backstop held, as it is saved, with its LZ/PZ's id; null when none is held or it is gone. */
    fun heldBackstop(): Pair<String, JsonObject>? {
        val held = _held.value as? HeldMeasure.Backstop ?: return null
        val open = diagrams.active.value?.takeIf { it.id == held.diagramId } ?: return null
        return DiagramOps.graphic(open, BACKSTOPS, held.id)?.let { open.id to it }
    }

    /** The FLOT or phase line held, as it is saved; null when none is held or it is gone. */
    fun heldLine(): JsonObject? {
        val held = _held.value as? HeldMeasure.Line ?: return null
        val set = setsNow().firstOrNull { it.id == held.setId } ?: return null
        return SetLines.of(set, held.key).firstOrNull { DiagramOps.idText(it["id"]) == held.id }
    }

    // -- Edits ------------------------------------------------------------------------------------------------------

    /** Deletes what is held. Null once deleted; else why not, in words. */
    fun deleteHeld(): String? = when (val held = _held.value) {
        null -> "Nothing is held."
        is HeldMeasure.Backstop -> {
            lockedReason(held.diagramId) ?: run {
                val open = diagrams.active.value?.takeIf { it.id == held.diagramId } ?: return "The LZ/PZ is no longer open."
                val graphic = DiagramOps.graphic(open, BACKSTOPS, held.id) ?: return GONE
                diagrams.edit("Delete backstop") { d -> DiagramOps.removeGraphic(d, BACKSTOPS, graphic["id"]) }
                _held.value = null
                null
            }
        }
        is HeldMeasure.Line -> editLine(held, if (held.key == SetLines.FLOTS) "Delete FLOT" else "Delete phase line") { null } ?: run {
            _held.value = null
            null
        }
    }

    /** Turns the held FLOT's scallops to its other side. */
    fun flipHeld(): String? {
        val held = _held.value as? HeldMeasure.Line ?: return "Nothing is held."
        if (held.key != SetLines.FLOTS) return "Only a FLOT is flipped."
        return editLine(held, "Flip FLOT") { Flots.flip(it) }
    }

    /** Gives the held phase line another name (`renamePhaseLine`); one with nothing in it is refused, in words. */
    fun renameHeld(name: String): String? {
        val held = _held.value as? HeldMeasure.Line ?: return "Nothing is held."
        if (held.key != SetLines.PHASE_LINES) return "Only a phase line has a name."
        if (PhaseLines.cleanName(name).isEmpty()) return "Give the phase line a name."
        return editLine(held, "Rename phase line") { PhaseLines.rename(it, name) }
    }

    /** The session holding set [setId] now, or null. */
    fun sessionFor(setId: String): DocumentSession<RouteSet>? = when {
        measures.active.value?.id == setId -> measures
        routes.active.value?.id == setId -> routes
        else -> null
    }

    /** Why the open pack would not let [documentId] change, in its words; null for the person's own, or a pack that may be changed. */
    fun lockedReason(documentId: String?): String? = pack.lockNow()?.reasonFor(documentId)

    // -- The set of control measures --------------------------------------------------------------------------------

    /** Opens [uuid] as the control measures (one the Library has), remembered for the next launch. False when it would not open. */
    suspend fun openSet(uuid: String): Boolean {
        if (!measures.open(uuid)) return false
        if (PackRef.parse(uuid) == null) last.remember(uuid)
        return true
    }

    /** Closes the control measures open now (its last change written first); a set of the person's own is forgotten for the next launch. */
    suspend fun closeSet() {
        val id = measures.active.value?.id ?: return
        if ((_held.value as? HeldMeasure.Line)?.setId == id) _held.value = null
        measures.close()
        if (PackRef.parse(id) == null) last.remember(null)
    }

    /**
     * Sign-out: the line half drawn and what is held are let go of, and the control measures closed (their last change written first; [DocumentSession.close] closes
     * whatever happens). Which set was last open is kept, as the other documents' are: an id names no place, and another account's is never found.
     */
    suspend fun signOut() {
        cancel()
        _held.value = null
        measures.close()
    }

    /** At launch, outside a pack, the control measures last open come back, unless something is open there already. Quietly: one that will not open is forgotten. */
    suspend fun restore() {
        if (measures.active.value != null || pack.state.value != null) return
        val id = last.id() ?: return
        if (!measures.open(id)) last.remember(null)
    }

    // -- Inside ---------------------------------------------------------------------------------------------------

    private fun setsOf(a: RouteSet?, b: RouteSet?): List<RouteSet> = listOfNotNull(a?.takeIf { it.mission == null }, b).distinctBy { it.id }

    // The open session a new line goes into now, if one does without anything being made or opened.
    private fun holderNow(): DocumentSession<RouteSet>? {
        val openPack = pack.state.value?.uuid
        val own = { set: RouteSet? -> set != null && (PackRef.parse(set.id)?.pack == openPack) }
        measures.active.value?.takeIf(own)?.let { return measures }
        routes.active.value?.takeIf { own(it) && it.mission == null && SetLines.isMeasures(it, it.name == SetLines.MEASURES_NAME) }?.let { return routes }
        return null
    }

    // Why [kind] may not be drawn in the open pack now: a backstop by its LZ/PZ, a line because the pack's measures would change.
    private fun lockedFor(kind: MeasureKind): String? {
        val lock = pack.lockNow() ?: return null
        return if (kind == MeasureKind.BACKSTOP) lock.reasonFor(diagrams.active.value?.id) else lock.reason
    }

    // [change] made to the line [held] names ([change]'s null takes it away), in its set's session as one step. Null once done; else why not.
    private fun editLine(held: HeldMeasure.Line, label: String, change: (JsonObject) -> JsonObject?): String? {
        lockedReason(held.setId)?.let { return it }
        val session = sessionFor(held.setId) ?: return "The control measures are no longer open."
        val set = session.active.value ?: return GONE
        if (SetLines.of(set, held.key).none { DiagramOps.idText(it["id"]) == held.id }) return GONE
        session.edit(label) { s ->
            val lines = SetLines.of(s, held.key).mapNotNull { line -> if (DiagramOps.idText(line["id"]) == held.id) change(line) else line }
            SetLines.withLines(s, held.key, lines)
        }
        return null
    }

    private fun label(kind: MeasureKind): String = if (kind == MeasureKind.FLOT) "Add FLOT" else "Add phase line"

    // The web's ids: `flot-<ms>-<random base 36>`, `pl-<ms>-<random base 36>`.
    private fun lineId(prefix: String, stamp: Long): String = "$prefix-$stamp-${java.lang.Long.toString((Math.random() * 1e9).toLong(), 36)}"

    companion object {
        /** A line needs two points (the web's "Add two points"). */
        const val MIN_POINTS = 2

        const val ADD_TWO = "Add two points."

        const val BACKSTOPS = "backstops"

        private const val GONE = "That is no longer here."
    }
}
