package app.ezpztac.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ezpztac.data.DiagramSession
import app.ezpztac.data.GraphicSelection
import app.ezpztac.geo.MgrsConverter
import app.ezpztac.geo.PlaceResult
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.AircraftProfile
import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.Doghouses
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.UnitDraft
import app.ezpztac.model.UnitMarkers
import app.ezpztac.model.Units
import app.ezpztac.planning.AircraftLookup
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.PlanningGraphics
import app.ezpztac.planning.RouteCalc
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/** The planning graphics the person can place. */
enum class GraphicKind(val collection: String, val label: String, val placeable: Boolean = true) {
    HELICOPTER("helicopters", "Helicopter"),
    PZ_MARKER("pzMarkers", "PZ marker"),
    SECTOR_OF_FIRE("sectorsOfFire", "Sector of fire"),
    GO_AROUND("goArounds", "Go-around"),

    /** The two standard doghouses (SP and RP) are made once, by the first analysis; the web has no way to add more, so neither does this. */
    DOGHOUSE("doghouses", "Doghouse", placeable = false),

    /** A unit is made in the unit builder, which says what symbol it is, so it is added by [GraphicsViewModel.addUnit] and not by a button of its own. */
    UNIT("units", "Unit", placeable = false);

    companion object {
        fun of(collection: String): GraphicKind? = entries.firstOrNull { it.collection == collection }
    }
}

/** One line of the list of graphics: what it is, where (as a grid, because crews work in MGRS), and anything worth saying about it. */
data class GraphicRowUi(
    val ref: GraphicRef,
    val kind: GraphicKind,
    val title: String,
    val grid: String?,
    val detail: String?,
    /** An aircraft that is too close to another. */
    val warning: Boolean,
    val selected: Boolean,
)

/** A doghouse's own fields, as the person reads and types them. [feeds] says which flight-data heading its heading sets, if either. */
data class DoghouseUi(val label: String, val time: String, val distanceKm: String, val airspeedKts: String, val feeds: String?)

/** The graphic that is held, with the controls that apply to its kind. */
data class InspectorUi(
    val ref: GraphicRef,
    val kind: GraphicKind,
    val title: String,
    val grid: String?,
    val latLon: String?,
    /** The way it points, degrees clockwise from north; null for what does not turn (a sector). */
    val rotation: Double?,
    /** How far a PZ marker reaches, in feet (the unit crews plan in). */
    val reachFt: Long?,
    /** `"left"` or `"right"` for a go-around. */
    val direction: String?,
    /** What a doghouse says besides its heading. */
    val doghouse: DoghouseUi? = null,
    /** The held unit as the unit builder shows it, and null for anything else. */
    val unit: UnitDraft? = null,
)

data class GraphicsUiState(
    /** Graphics are placed only once the diagram is analysed: until then it has no landing zone for them to sit in. */
    val canEdit: Boolean = false,
    val rows: List<GraphicRowUi> = emptyList(),
    val inspector: InspectorUi? = null,
    /** The separation alerts, in the web's words. */
    val alerts: List<String> = emptyList(),
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
    val error: String? = null,
)

/**
 * Placing and editing the planning graphics of the open diagram from the sheet: place one at the crosshair, pick it from the list (or the
 * map), nudge it, turn it, put it at the crosshair or at a grid, delete it. Every change is one undo step, through [DiagramSession.edit].
 * Dragging on the map joins these later; this is the way that works with gloves and in a vibrating cockpit, and for a screen reader.
 */
@HiltViewModel
class GraphicsViewModel @Inject constructor(
    private val session: DiagramSession,
    private val selection: GraphicSelection,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    /** Aircraft are measured as UH-60Ls until aircraft profiles can be chosen. */
    private val profiles = listOf(AircraftProfile.FALLBACK)
    private val active = AircraftProfile.FALLBACK

    val state: StateFlow<GraphicsUiState> = combine(
        session.active, selection.selected, session.undoDepth, session.redoDepth, error,
    ) { diagram, selected, undo, redo, error -> uiStateOf(diagram, selected, undo, redo, error) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, GraphicsUiState())

    // -- Placing -----------------------------------------------------------------------------------------------------------

    /** Puts a new [kind] of graphic at [at] (the crosshair) and selects it. An aircraft is nudged clear of those already down. */
    fun place(kind: GraphicKind, at: LatLon?, direction: String? = null) {
        val diagram = session.active.value ?: return
        if (!diagram.canEditGraphics || !kind.placeable) return
        if (at == null) return fail("Move the map to where it should go first.")
        val existing = existingKeys(diagram, kind.collection)
        val made: JsonObject? = when (kind) {
            GraphicKind.HELICOPTER -> {
                val aircraft = diagram.graphics.helicopters.mapNotNull(PlanningGraphics.Aircraft::of)
                PlanningGraphics.placeHelicopter(at, aircraft, active, { AircraftLookup.profileForAsset(it.profileRef, profiles, active) }, JsonPrimitive(newNumericId(existing)))
            }
            GraphicKind.PZ_MARKER -> PlanningGraphics.createPzMarker(at.lat, at.lon, newTextId("pz", existing))
            GraphicKind.SECTOR_OF_FIRE -> PlanningGraphics.createSectorOfFire(at.lat, at.lon, newTextId("sec", existing))
            GraphicKind.GO_AROUND -> PlanningGraphics.createGoAround(at.lat, at.lon, direction ?: "left", newTextId("ga", existing))
            GraphicKind.DOGHOUSE, GraphicKind.UNIT -> null
        }
        if (made == null) return fail("That is not a position.")
        error.value = null
        session.edit("Place ${kind.label.lowercase()}") { DiagramOps.upsertGraphic(it, kind.collection, made) }
        selection.select(GraphicRef(kind.collection, DiagramOps.idText(made["id"])))
    }

    /**
     * Adds a unit made in the unit builder, standing at [at] (the crosshair), and holds it. The web puts a new unit a little off the target at
     * random; here it is where the person pointed, so it can be found.
     */
    fun addUnit(draft: UnitDraft, at: LatLon?) {
        val diagram = session.active.value ?: return
        if (!diagram.canEditGraphics) return
        if (at == null) return fail("Move the map to where it should go first.")
        val id = newTextId("unit", existingKeys(diagram, "units"))
        error.value = null
        session.edit("Place unit") { DiagramOps.upsertGraphic(it, "units", UnitMarkers.create(draft.config, at, 0.0, id)) }
        selection.select(GraphicRef("units", id))
    }

    /** Applies what the unit builder holds to the held unit: its symbol and labels, nothing else. */
    fun updateUnit(draft: UnitDraft) = edit("Unit") { c, _ -> if (c == "units") draft.patch else null }

    /** The web makes a helicopter's id from the clock; the same here, nudged past any that is taken. */
    private fun newNumericId(taken: Set<String>): Long {
        var id = System.currentTimeMillis()
        while (id.toString() in taken) id += 1
        return id
    }

    private fun newTextId(prefix: String, taken: Set<String>): String {
        var id = System.currentTimeMillis()
        while ("$prefix-$id" in taken) id += 1
        return "$prefix-$id"
    }

    private fun existingKeys(diagram: Diagram, collection: String): Set<String> = when (collection) {
        "helicopters" -> diagram.graphics.helicopters
        "pzMarkers" -> diagram.graphics.pzMarkers
        "sectorsOfFire" -> diagram.graphics.sectorsOfFire
        "goArounds" -> diagram.graphics.goArounds
        "doghouses" -> diagram.graphics.doghouses
        "units" -> diagram.graphics.units
        else -> emptyList()
    }.mapNotNull { (it as? JsonObject)?.get("id") }.map { DiagramOps.idText(it) }.toSet()

    // -- Choosing ----------------------------------------------------------------------------------------------------------

    /** A tap on a graphic (in the list or on the map) holds it; a second tap puts it down. */
    fun select(ref: GraphicRef) = selection.toggle(ref)

    fun deselect() = selection.clear()

    // -- Editing the one that is held --------------------------------------------------------------------------------------

    private fun edit(label: String, refusal: String = "That cannot be done to this graphic.", patch: (String, JsonObject) -> JsonObject?) {
        val ref = selection.selected.value ?: return
        val diagram = session.active.value ?: return
        val graphic = DiagramOps.graphic(diagram, ref.collection, ref.key) ?: return
        val changes = patch(ref.collection, graphic) ?: return fail(refusal)
        error.value = null
        session.edit(label) { DiagramOps.patchGraphic(it, ref.collection, graphic["id"], changes) }
    }

    /** Moves the held graphic [northFt] feet north and [eastFt] east (negative: south, west). */
    fun nudge(northFt: Double, eastFt: Double) = edit("Move") { c, g -> GraphicEdits.nudge(c, g, northFt / Units.METERS_TO_FEET, eastFt / Units.METERS_TO_FEET) }

    /** Puts the held graphic at the crosshair. */
    fun moveToCrosshair(at: LatLon?) {
        if (at == null) return fail("Move the map to where it should go first.")
        edit("Move") { c, g -> GraphicEdits.moveTo(c, g, at) }
    }

    /** Puts the held graphic at a grid or coordinate the person typed. */
    fun moveToText(text: String) {
        when (val place = PlaceSearch.resolve(text)) {
            is PlaceResult.Found -> edit("Move") { c, g -> GraphicEdits.moveTo(c, g, place.at) }
            is PlaceResult.NotUnderstood -> fail(place.message)
        }
    }

    fun rotateBy(degrees: Double) = edit("Turn") { c, g -> GraphicEdits.rotateBy(c, g, degrees) }

    fun setRotation(degrees: Double) = edit("Turn") { c, g -> GraphicEdits.setRotation(c, g, degrees) }

    /** Lengthens or shortens the held PZ marker by [deltaFt] feet. */
    fun changeReach(deltaFt: Double) = edit("PZ reach") { c, g ->
        if (c == "pzMarkers") GraphicEdits.pzReachM(g)?.let { GraphicEdits.setPzReach(g, it + deltaFt / Units.METERS_TO_FEET) } else null
    }

    fun tipToCrosshair(at: LatLon?) {
        if (at == null) return fail("Move the map to where it should go first.")
        edit("PZ tip") { c, g -> if (c == "pzMarkers") GraphicEdits.setPzTip(g, at) else null }
    }

    fun setDirection(direction: String) = edit("Go-around") { c, g -> if (c == "goArounds") GraphicEdits.setGoAroundDirection(g, direction) else null }

    // The held doghouse's own fields. What is typed is checked for what the field is and stored as the web stores it; when it is not what the
    // field is for, the answer is the words to show under the field (not the panel's banner, which may be scrolled out of sight).

    private fun editDoghouse(label: String, refusal: String, patch: JsonObject?): String? {
        if (patch == null) return refusal
        edit(label) { c, _ -> if (c == "doghouses") patch else null }
        return null
    }

    fun setDoghouseLabel(typed: String): String? =
        editDoghouse("Doghouse label", "Enter a label of up to ${Doghouses.MAX_LABEL} characters, such as [SP1].", Doghouses.labelPatch(typed))

    fun setDoghouseTime(typed: String): String? = editDoghouse("Doghouse time", "Enter a time as minutes and seconds, such as 03+20.", Doghouses.timePatch(typed))

    fun setDoghouseDistance(typed: String): String? = editDoghouse("Doghouse distance", "Enter a distance in kilometres, such as 3.1.", Doghouses.distancePatch(typed))

    fun setDoghouseAirspeed(typed: String): String? = editDoghouse("Doghouse airspeed", "Enter an airspeed in knots, such as 60.", Doghouses.airspeedPatch(typed))

    /** Removes the held graphic. It can be brought back with undo. */
    fun delete() {
        val ref = selection.selected.value ?: return
        val diagram = session.active.value ?: return
        val graphic = DiagramOps.graphic(diagram, ref.collection, ref.key) ?: return
        selection.clear()
        session.edit("Delete ${GraphicKind.of(ref.collection)?.label?.lowercase() ?: "graphic"}") { DiagramOps.removeGraphic(it, ref.collection, graphic["id"]) }
    }

    fun undo() {
        session.undo()
    }

    fun redo() {
        session.redo()
    }

    fun dismissError() {
        error.value = null
    }

    private fun fail(message: String) {
        error.value = message
    }

    // -- What the sheet shows ---------------------------------------------------------------------------------------------

    private fun uiStateOf(diagram: Diagram?, selected: GraphicRef?, undo: Int, redo: Int, error: String?): GraphicsUiState {
        if (diagram == null || !diagram.canEditGraphics) return GraphicsUiState(undoDepth = undo, redoDepth = redo, error = error)
        val resolve = { a: PlanningGraphics.Aircraft -> AircraftLookup.profileForAsset(a.profileRef, profiles, active) }
        val aircraft = diagram.graphics.helicopters.mapNotNull(PlanningGraphics.Aircraft::of)
        val alerts = PlanningGraphics.separationAlerts(aircraft, resolve)
        val violating = alerts.flatMap { listOf(DiagramOps.idText(it.first), DiagramOps.idText(it.second)) }.toSet()

        val rows = GraphicKind.entries.flatMap { kind ->
            items(diagram, kind.collection).mapNotNull { saved ->
                val o = saved as? JsonObject ?: return@mapNotNull null
                val ref = GraphicRef(kind.collection, DiagramOps.idText(o["id"]))
                val at = GraphicEdits.position(kind.collection, o)
                GraphicRowUi(
                    ref = ref, kind = kind, title = titleOf(kind, o, aircraft, resolve), grid = at?.let(::gridOf), detail = detailOf(kind, o),
                    warning = kind == GraphicKind.HELICOPTER && ref.key in violating, selected = ref == selected,
                )
            }
        }
        val held = rows.firstOrNull { it.selected }?.let { row ->
            val o = DiagramOps.graphic(diagram, row.ref.collection, row.ref.key) ?: return@let null
            val at = GraphicEdits.position(row.ref.collection, o)
            InspectorUi(
                ref = row.ref, kind = row.kind, title = row.title, grid = row.grid, latLon = at?.let { "%.5f, %.5f".format(java.util.Locale.ROOT, it.lat, it.lon) },
                rotation = GraphicEdits.rotation(row.ref.collection, o), reachFt = if (row.kind == GraphicKind.PZ_MARKER) GraphicEdits.pzReachM(o)?.let(::feetOf) else null,
                direction = if (row.kind == GraphicKind.GO_AROUND) (o["direction"] as? JsonPrimitive)?.content else null,
                doghouse = if (row.kind == GraphicKind.DOGHOUSE) doghouseUi(o) else null,
                unit = if (row.kind == GraphicKind.UNIT) UnitDraft.of(o) else null,
            )
        }
        return GraphicsUiState(canEdit = true, rows = rows, inspector = held, alerts = alerts.map { it.message }, undoDepth = undo, redoDepth = redo, error = error)
    }

    private fun items(diagram: Diagram, collection: String) = when (collection) {
        "helicopters" -> diagram.graphics.helicopters
        "pzMarkers" -> diagram.graphics.pzMarkers
        "sectorsOfFire" -> diagram.graphics.sectorsOfFire
        "goArounds" -> diagram.graphics.goArounds
        "doghouses" -> diagram.graphics.doghouses
        "units" -> diagram.graphics.units
        else -> emptyList()
    }

    private fun doghouseUi(saved: JsonObject): DoghouseUi {
        val shown = Doghouses.display(saved, Doghouses.rotation(saved))
        return DoghouseUi(
            label = shown.id.orEmpty(), time = "${shown.minutes}+${shown.seconds}", distanceKm = shown.distanceText, airspeedKts = shown.airspeedText,
            feeds = when {
                Doghouses.isLanding(saved) -> "Sets the landing heading on the LZ card"
                Doghouses.isTakeoff(saved) -> "Sets the takeoff heading on the LZ card"
                else -> null
            },
        )
    }

    /** `Unit · A/1-171`, or the function when there is no designation; an older unit that was an image says so. */
    private fun unitTitle(saved: JsonObject): String {
        if ((saved["sidc"] as? JsonPrimitive)?.content.isNullOrEmpty()) return "Unit (image)"
        val draft = UnitDraft.of(saved)
        return "Unit · ${draft.uniqueDesignation.ifBlank { draft.functionLabel ?: draft.functionId }}"
    }

    private fun unitDetail(saved: JsonObject): String? {
        if ((saved["sidc"] as? JsonPrimitive)?.content.isNullOrEmpty()) return null
        val draft = UnitDraft.of(saved)
        return listOfNotNull(draft.affiliationLabel, draft.functionLabel, draft.echelonLabel).joinToString(" · ").ifEmpty { null }
    }

    private fun feetOf(meters: Double): Long = RouteCalc.jsRound(meters * Units.METERS_TO_FEET).toLong()

    private fun gridOf(at: LatLon): String? = MgrsConverter.toMgrs(at.lat, at.lon)?.format()

    private fun titleOf(kind: GraphicKind, saved: JsonObject, aircraft: List<PlanningGraphics.Aircraft>, resolve: (PlanningGraphics.Aircraft) -> AircraftProfile): String = when (kind) {
        GraphicKind.DOGHOUSE -> Doghouses.display(saved, 0.0).id?.takeIf { it.isNotBlank() }?.let { "Doghouse · $it" } ?: kind.label
        GraphicKind.UNIT -> unitTitle(saved)
        GraphicKind.HELICOPTER -> {
            val key = DiagramOps.idText(saved["id"])
            val own = aircraft.firstOrNull { DiagramOps.idText(it.id) == key }
            "Helicopter · ${own?.let { resolve(it).designation } ?: active.designation}"
        }
        else -> kind.label
    }

    private fun detailOf(kind: GraphicKind, saved: JsonObject): String? = when (kind) {
        GraphicKind.HELICOPTER, GraphicKind.GO_AROUND -> GraphicEdits.rotation(kind.collection, saved)?.let { "heading ${it.toLong()}°" }
        GraphicKind.PZ_MARKER -> GraphicEdits.pzReachM(saved)?.let { "reach ${feetOf(it)} ft" }
        GraphicKind.SECTOR_OF_FIRE -> null
        GraphicKind.UNIT -> unitDetail(saved)
        GraphicKind.DOGHOUSE -> Doghouses.display(saved, Doghouses.rotation(saved)).let { "${it.heading}° · ${it.minutes}+${it.seconds} · ${it.distanceText} km · ${it.airspeedText} kts" }
    }?.let { text -> if (kind == GraphicKind.GO_AROUND) (saved["direction"] as? JsonPrimitive)?.content?.let { "$it · $text" } ?: text else text }
}
