package app.ezpztac.data

import app.ezpztac.model.Diagram
import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.BackstopArrow
import app.ezpztac.planning.Backstops
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.SetLines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picking something up on the map with a long press and putting it down somewhere else.
 *
 * The thing follows the finger as it moves, by changing the document it is in at once (so the map redraws it, and what depends on it, such as the separation of two
 * aircraft, follows), and the whole drag is **one step to undo** ([DocumentSession.edit]'s `coalesce`). Where it ends up is where it started plus how far the finger has
 * travelled, measured on the ground, so it does not jump to the finger and a graphic of any shape (a PZ marker with its tip, a sector with its corners) keeps its shape.
 *
 * What can be moved, and how it is saved:
 * - **A planning graphic** of the open diagram: `GraphicEdits.nudge` makes the patch, merged into the saved graphic, so any field a newer web release adds is untouched.
 * - **A point of a route** of the open set, named or shaping. It forgets the ground elevation fetched for the old place (as a nudge does). In an imported mission, the web
 *   moves the *same point* of another route with it (AMPS keeps a hand-off point as two points that share a name and a place), and so does this.
 * - **A threat**: shown at once, written when the finger lifts ([ThreatStore.previewMove]), since what it is written to is rewritten whole. Threats have no undo.
 * - **A backstop's arrow** (the web's `Backstop.jsx`): moved whole by the same change of latitude and longitude as the finger ([Backstops.moved]), or by its tip
 *   ([Backstops.aimed]: a tip dropped within 20 px of the target goes back onto it, and a tip picked up from the target and put back on it changes nothing). The
 *   arrow is saved whole, as the web's `{arrow}` patch replaces it; the line it explains never moves.
 * - **A point of a FLOT or a phase line**, by the finger's change of latitude and longitude; the line's points are saved whole, as the web's `{points}` patch is.
 *
 * It is told by the gesture (`start`, `move`, `end`, `cancel`) and knows nothing of a screen; what was under the finger is the caller's to work out.
 */
@Singleton
class MapDrag @Inject constructor(
    private val session: DiagramSession,
    private val routeSession: RouteSession,
    private val threats: ThreatStore,
    private val measures: ControlMeasures? = null,
) {
    private sealed interface Origin {
        class OfGraphic(val ref: GraphicRef, val graphic: JsonObject) : Origin
        class OfPzTip(val ref: GraphicRef, val graphic: JsonObject, val tip: LatLon) : Origin
        class OfPoint(val routeId: String, val moves: List<Pair<String, RoutePointAt>>) : Origin
        class OfThreat(val id: String, val at: LatLon) : Origin
        class OfArrow(val ref: GraphicRef, val graphic: JsonObject, val arrow: BackstopArrow) : Origin
        class OfTip(val ref: GraphicRef, val graphic: JsonObject, val arrow: BackstopArrow, val target: LatLon?) : Origin {
            var latest: JsonObject? = null
        }
        class OfVertex(val session: DocumentSession<RouteSet>, val target: DragTarget.LineVertex, val line: JsonObject, val at: LatLon) : Origin
    }

    /** A point's place when the drag began, with the route it is in. */
    private data class RoutePointAt(val pointId: String, val at: LatLon)

    private class Drag(
        val target: DragTarget,
        val start: LatLon,
        val origin: Origin,
        val pixelsBetween: ((LatLon, LatLon) -> Double)?,
        val key: Any = Any(),
    )

    private var drag: Drag? = null

    private val _active = MutableStateFlow<DragTarget?>(null)

    /** What is being dragged, for a screen that wants to say so (and to hold off other things while it is). */
    val active: StateFlow<DragTarget?> = _active.asStateFlow()

    /**
     * Picks [target] up at [at] (where the finger is). False when it cannot be picked up (it is not there, or has no position): the gesture then goes on as a pan.
     * [pixelsBetween] is how far apart two places are on the screen, in pixels; a backstop's tip needs it (the camera does not move while something is dragged).
     */
    fun start(target: DragTarget, at: LatLon, pixelsBetween: ((LatLon, LatLon) -> Double)? = null): Boolean {
        cancel()
        val origin = when (target) {
            is DragTarget.Graphic -> {
                val diagram = session.active.value ?: return false
                val graphic = DiagramOps.graphic(diagram, target.ref.collection, target.ref.key) ?: return false
                if (GraphicEdits.position(target.ref.collection, graphic) == null) return false
                Origin.OfGraphic(target.ref, graphic)
            }
            is DragTarget.PzTip -> {
                val diagram = session.active.value ?: return false
                val graphic = DiagramOps.graphic(diagram, target.ref.collection, target.ref.key) ?: return false
                val tip = GraphicEdits.pzTip(graphic) ?: return false
                Origin.OfPzTip(target.ref, graphic, tip)
            }
            is DragTarget.RoutePoint -> {
                val set = routeSession.active.value ?: return false
                val route = set.route(target.routeId) ?: return false
                val point = route.points.firstOrNull { it.id == target.pointId } ?: return false
                Origin.OfPoint(target.routeId, withTwins(set, target.routeId, point))
            }
            is DragTarget.Threat -> {
                val entry = threats.entries.value.firstOrNull { it.id == target.id } ?: return false
                Origin.OfThreat(entry.id, LatLon(entry.threat.lat, entry.threat.lon))
            }
            is DragTarget.BackstopArrow -> {
                val diagram = session.active.value ?: return false
                val graphic = DiagramOps.graphic(diagram, target.ref.collection, target.ref.key) ?: return false
                val arrow = Backstops.arrow(graphic, diagram.target?.let { LatLon(it.lat, it.lon) }) ?: return false
                Origin.OfArrow(target.ref, graphic, arrow)
            }
            is DragTarget.BackstopTip -> {
                if (pixelsBetween == null) return false
                val diagram = session.active.value ?: return false
                val graphic = DiagramOps.graphic(diagram, target.ref.collection, target.ref.key) ?: return false
                val aim = diagram.target?.let { LatLon(it.lat, it.lon) }
                val arrow = Backstops.arrow(graphic, aim) ?: return false
                Origin.OfTip(target.ref, graphic, arrow, aim)
            }
            is DragTarget.LineVertex -> {
                val lineSession = measures?.sessionFor(target.setId) ?: return false
                val set = lineSession.active.value ?: return false
                val line = SetLines.of(set, target.key).firstOrNull { DiagramOps.idText(it["id"]) == target.lineId } ?: return false
                val point = SetLines.places(line).getOrNull(target.index) ?: return false
                Origin.OfVertex(lineSession, target, line, point)
            }
        }
        drag = Drag(target, at, origin, pixelsBetween)
        _active.value = target
        return true
    }

    /** The finger is now at [to]. */
    fun move(to: LatLon) {
        val d = drag ?: return
        val (northM, eastM) = GraphicEdits.metresBetween(d.start, to)
        when (val origin = d.origin) {
            is Origin.OfGraphic -> {
                val patch = GraphicEdits.nudge(origin.ref.collection, origin.graphic, northM, eastM) ?: return
                session.edit("Move", d.key) { DiagramOps.patchGraphic(it, origin.ref.collection, origin.graphic["id"], patch) }
            }
            is Origin.OfPzTip -> {
                // Only the tip: the anchor stays, so the marker's reach and bearing change.
                val tip = GraphicEdits.offset(origin.tip, northM, eastM)
                val patch = GraphicEdits.setPzTip(origin.graphic, tip) ?: return
                session.edit("PZ tip", d.key) { DiagramOps.patchGraphic(it, origin.ref.collection, origin.graphic["id"], patch) }
            }
            is Origin.OfPoint -> routeSession.edit("Move point", d.key) { set ->
                origin.moves.fold(set) { acc, (routeId, point) ->
                    val moved = GraphicEdits.offset(point.at, northM, eastM)
                    acc.withPointMoved(routeId, point.pointId) { moved }
                }
            }
            is Origin.OfThreat -> {
                val moved = GraphicEdits.offset(origin.at, northM, eastM)
                threats.previewMove(origin.id, moved.lat, moved.lon)
            }
            is Origin.OfArrow -> {
                val arrow = Backstops.moved(origin.arrow, to.lat - d.start.lat, to.lon - d.start.lon)
                session.edit("Move backstop", d.key) { putBackstop(it, origin.ref, JsonObject(origin.graphic + ("arrow" to arrow))) }
            }
            is Origin.OfTip -> {
                val pixels = d.pixelsBetween ?: return
                // Within a finger of its tail the arrow has no direction to take: it stays as it was last drawn.
                val aimed = Backstops.aimed(origin.arrow, to, origin.target) { place -> pixels(place, to) } ?: return
                origin.latest = aimed
                session.edit("Aim backstop", d.key) { putBackstop(it, origin.ref, JsonObject(origin.graphic + ("arrow" to aimed))) }
            }
            is Origin.OfVertex -> {
                val moved = LatLon(origin.at.lat + (to.lat - d.start.lat), origin.at.lon + (to.lon - d.start.lon))
                val points = SetLines.points(origin.line).mapIndexed { i, p -> if (i == origin.target.index) SetLines.plainPoint(moved) else p }
                val line = JsonObject(origin.line + ("points" to JsonArray(points)))
                origin.session.edit("Move point", d.key) { set ->
                    val lines = SetLines.of(set, origin.target.key).map { if (DiagramOps.idText(it["id"]) == origin.target.lineId) line else it }
                    SetLines.withLines(set, origin.target.key, lines)
                }
            }
        }
    }

    // [graphic] in place of the backstop [ref] names, whole: an arrow put back on the target has no `tip`, which a merged patch could not take away.
    private fun putBackstop(diagram: Diagram, ref: GraphicRef, graphic: JsonObject): Diagram =
        DiagramOps.replaceGraphic(diagram, ref.collection, graphic["id"], graphic)

    // The backstop as it was picked up: what a drag that changed nothing, or was taken from us, leaves.
    private fun putBack(origin: Origin, key: Any) {
        when (origin) {
            is Origin.OfArrow -> session.edit("Move backstop", key) { putBackstop(it, origin.ref, origin.graphic) }
            is Origin.OfTip -> session.edit("Aim backstop", key) { putBackstop(it, origin.ref, origin.graphic) }
            else -> Unit
        }
    }

    /** The finger lifted at [at]: the thing is put down there. */
    fun end(at: LatLon) {
        val d = drag ?: return
        move(at)
        if (d.origin is Origin.OfThreat) threats.settle()
        // A tip picked up from the target and put back on it has changed nothing (the web's `latest.tip || !start.onTarget`).
        val origin = d.origin
        if (origin is Origin.OfTip) {
            val latest = origin.latest
            if (latest == null || (latest["tip"] == null && origin.arrow.onTarget)) putBack(origin, d.key)
        }
        finish()
    }

    /** The gesture was taken from us (a second finger, the system): the thing goes back where it was picked up from. */
    fun cancel() {
        val d = drag ?: return
        if (d.origin is Origin.OfArrow || d.origin is Origin.OfTip) putBack(d.origin, d.key) else move(d.start)
        if (d.origin is Origin.OfThreat) threats.settle()
        finish()
    }

    /**
     * The drag ends where the thing is, with no edit of its own: what can no longer be changed (its pack turned read-only) is not moved back either, since moving it back
     * would itself be a change. What the pack already took stays; what it did not is put back to the pack's version by the pack's own rule.
     */
    fun abandon() {
        val d = drag ?: return
        if (d.origin is Origin.OfThreat) threats.settle()
        finish()
    }

    private fun finish() {
        drag = null
        _active.value = null
    }

    /**
     * The points that move with [point]: itself, and in an imported mission the point of any other route that shares its name and its place (the web's rule: AMPS keeps a
     * hand-off point, such as an LZ that ends one route and starts the next, as two separate points). A point with no name moves alone.
     */
    private fun withTwins(set: RouteSet, routeId: String, point: RoutePoint): List<Pair<String, RoutePointAt>> {
        val id = requireNotNull(point.id)
        val own = routeId to RoutePointAt(id, LatLon(point.lat, point.lon))
        val name = point.name?.removePrefix(".")?.takeIf { it.isNotEmpty() }
        if (set.mission == null || name == null) return listOf(own)
        val twins = set.routes.filter { it.id != routeId }.flatMap { route ->
            route.points.filter { p ->
                p.id != null && p.name?.removePrefix(".") == name && p.lat == point.lat && p.lon == point.lon
            }.map { route.id to RoutePointAt(it.id!!, LatLon(it.lat, it.lon)) }
        }
        return listOf(own) + twins
    }
}
