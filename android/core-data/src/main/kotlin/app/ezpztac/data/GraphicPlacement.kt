package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.LatLon
import app.ezpztac.planning.AircraftLookup
import app.ezpztac.planning.PlanningGraphics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** A planning tool that puts a graphic down: what it makes, into which of the diagram's lists, and what it is called once and many times. */
enum class PlaceTool(val collection: String, val noun: String, val plural: String, val direction: String? = null) {
    HELICOPTER("helicopters", "helicopter", "helicopters"),
    PZ_MARKER("pzMarkers", "PZ marker", "PZ markers"),
    SECTOR_OF_FIRE("sectorsOfFire", "sector of fire", "sectors of fire"),
    GO_AROUND_LEFT("goArounds", "left go-around", "left go-arounds", "left"),
    GO_AROUND_RIGHT("goArounds", "right go-around", "right go-arounds", "right"),
}

/**
 * The planning tools as modes (docs/native-design: "explicit modes that place at the crosshair or a tap, with Done and Cancel"). While one is on, a tap
 * on the map puts its graphic there (`HomeViewModel.mapTapped` asks this before anything else is held), and so does *Add at the crosshair*. What a mode
 * places in a row is one step to undo (an edit in between starts another), *Cancel* takes back everything it placed, and *Done* keeps it. The mode belongs
 * to the LZ/PZ it was begun on and ends when another opens.
 *
 * A mode has the map's taps as a boundary or a route being drawn does, so it takes [DrawingMode] and is refused while either is drawn (and they while it is on).
 */
@Singleton
class GraphicPlacement @Inject constructor(
    private val session: DiagramSession,
    private val aircraft: AircraftProfiles,
    private val drawing: DrawingMode,
) {
    /** A mode under way: the LZ/PZ it is on, its tool, and the ids of what it has placed so far. */
    data class Placing(val diagramId: String, val tool: PlaceTool, val placed: List<JsonElement> = emptyList())

    private val _placing = MutableStateFlow<Placing?>(null)

    /** The mode under way, or null. */
    val placing: StateFlow<Placing?> = _placing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last thing asked of the mode could not be done, in words for its toolbar. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** What this mode's edits are merged under, so they are one step to undo; a new one for each mode. */
    private var key: Any? = null

    /** Turns [tool]'s mode on. Null when it is on; else why not, in words. Another tool's mode that is on is finished first (what it placed stays). */
    @Synchronized
    fun begin(tool: PlaceTool): String? {
        val diagram = session.active.value ?: return refuse("Open an LZ/PZ first.")
        if (!diagram.canEditGraphics) return refuse("Analyze the LZ/PZ first: its graphics are placed in its landing zone.")
        val current = _placing.value
        if (current != null && current.diagramId == diagram.id && current.tool == tool) return null
        if (current != null) end()
        drawing.begin(DrawingMode.Kind.PLACEMENT)?.let { return refuse(it) }
        key = Any()
        _error.value = null
        _placing.value = Placing(diagram.id, tool)
        return null
    }

    /** Puts the mode's graphic at [at] (where the map was tapped, or the crosshair). Null when it was placed; else why not. Nothing happens with no mode on. */
    @Synchronized
    fun placeAt(at: LatLon?): String? {
        val mode = _placing.value ?: return null
        if (at == null) return refuse("Move the map to where it should go first.")
        val diagram = session.active.value
        if (diagram == null || diagram.id != mode.diagramId || !diagram.canEditGraphics) {
            end()                                                                     // the LZ/PZ it was on has gone, or lost its analysis
            return null
        }
        val made = make(mode.tool, diagram, at) ?: return refuse("That is not a position.")
        _error.value = null
        session.edit("Place ${mode.tool.plural}", coalesce = key) { DiagramOps.upsertGraphic(it, mode.tool.collection, made) }
        _placing.value = mode.copy(placed = mode.placed + (made["id"] ?: JsonPrimitive("")))
        return null
    }

    /** *Done*: the mode ends and what it placed stays. */
    @Synchronized
    fun finish() = end()

    /**
     * *Cancel*: everything the mode placed is taken back as if it had never been placed (`DocumentSession.retract`), then it ends. Neither undo nor redo
     * brings any of it back, and whatever was done in between (an edit of something else, an undo) stays.
     */
    @Synchronized
    fun cancel() {
        val mode = _placing.value ?: return
        val modeKey = key
        if (session.active.value?.id == mode.diagramId && modeKey != null) {
            session.retract(modeKey) { diagram -> mode.placed.fold(diagram) { d, id -> DiagramOps.removeGraphic(d, mode.tool.collection, id) } }
        }
        end()
    }

    /** Ends the mode without touching what it placed: another LZ/PZ opened, or the open one closed. */
    @Synchronized
    fun end() {
        if (_placing.value == null && key == null) return
        _placing.value = null
        key = null
        _error.value = null
        drawing.end(DrawingMode.Kind.PLACEMENT)
    }

    fun dismissError() {
        _error.value = null
    }

    private fun refuse(message: String): String {
        _error.value = message
        return message
    }

    /**
     * The graphic [tool] makes at [at] on [diagram], with an id none of its kind there has, or null when [at] is not a position. An aircraft is placed as
     * the mission aircraft and nudged clear of those already down; the rest are made as the web makes them.
     */
    fun make(tool: PlaceTool, diagram: Diagram, at: LatLon): JsonObject? {
        val existing = existingIds(diagram, tool.collection)
        return when (tool) {
            PlaceTool.HELICOPTER -> {
                val profiles = aircraft.profiles.value
                val active = aircraft.active.value
                val down = diagram.graphics.helicopters.mapNotNull(PlanningGraphics.Aircraft::of)
                PlanningGraphics.placeHelicopter(at, down, active, { AircraftLookup.profileForAsset(it.profileRef, profiles, active) }, JsonPrimitive(newNumericId(existing)))
            }
            PlaceTool.PZ_MARKER -> PlanningGraphics.createPzMarker(at.lat, at.lon, newTextId("pz", existing))
            PlaceTool.SECTOR_OF_FIRE -> PlanningGraphics.createSectorOfFire(at.lat, at.lon, newTextId("sec", existing))
            PlaceTool.GO_AROUND_LEFT, PlaceTool.GO_AROUND_RIGHT -> PlanningGraphics.createGoAround(at.lat, at.lon, tool.direction ?: "left", newTextId("ga", existing))
        }
    }

    companion object {
        /** The web makes a helicopter's id from the clock; the same here, nudged past any that is taken, so two in one millisecond do not collide. */
        fun newNumericId(taken: Set<String>): Long {
            var id = System.currentTimeMillis()
            while (id.toString() in taken) id += 1
            return id
        }

        fun newTextId(prefix: String, taken: Set<String>): String {
            var id = System.currentTimeMillis()
            while ("$prefix-$id" in taken) id += 1
            return "$prefix-$id"
        }

        /** The ids of [collection]'s graphics on [diagram], as text. */
        fun existingIds(diagram: Diagram, collection: String): Set<String> = when (collection) {
            "helicopters" -> diagram.graphics.helicopters
            "pzMarkers" -> diagram.graphics.pzMarkers
            "sectorsOfFire" -> diagram.graphics.sectorsOfFire
            "goArounds" -> diagram.graphics.goArounds
            "doghouses" -> diagram.graphics.doghouses
            "units" -> diagram.graphics.units
            else -> emptyList()
        }.mapNotNull { (it as? JsonObject)?.get("id") }.map { DiagramOps.idText(it) }.toSet()
    }
}
