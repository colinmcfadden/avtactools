package app.ezpztac.data

import app.ezpztac.model.DiagramOps
import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.GraphicEdits
import app.ezpztac.planning.SketchOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - **A threat**: shown at once, written to its sealed file when the finger lifts ([ThreatStore.previewMove]). Threats are local to the device and have no undo.
 *
 * It is told by the gesture (`start`, `move`, `end`, `cancel`) and knows nothing of a screen; what was under the finger is the caller's to work out.
 */
@Singleton
class MapDrag @Inject constructor(
    private val session: DiagramSession,
    private val routeSession: RouteSession,
    private val threats: ThreatStore,
) {
    private sealed interface Origin {
        class OfGraphic(val ref: GraphicRef, val graphic: JsonObject) : Origin
        class OfPzTip(val ref: GraphicRef, val graphic: JsonObject, val tip: LatLon) : Origin
        class OfPoint(val routeId: String, val moves: List<Pair<String, RoutePointAt>>) : Origin
        class OfThreat(val id: String, val at: LatLon) : Origin
    }

    /** A point's place when the drag began, with the route it is in. */
    private data class RoutePointAt(val pointId: String, val at: LatLon)

    private class Drag(val target: DragTarget, val start: LatLon, val origin: Origin, val key: Any = Any())

    private var drag: Drag? = null

    private val _active = MutableStateFlow<DragTarget?>(null)

    /** What is being dragged, for a screen that wants to say so (and to hold off other things while it is). */
    val active: StateFlow<DragTarget?> = _active.asStateFlow()

    /** Picks [target] up at [at] (where the finger is). False when it cannot be picked up (it is not there, or has no position): the gesture then goes on as a pan. */
    fun start(target: DragTarget, at: LatLon): Boolean {
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
        }
        drag = Drag(target, at, origin)
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
                    acc.mapRoute(routeId) { r ->
                        SketchOps.move(r, point.pointId, moved.lat, moved.lon, null).let { m -> if (point.pointId in m.elevations) m.copy(elevations = m.elevations - point.pointId) else m }
                    }
                }
            }
            is Origin.OfThreat -> {
                val moved = GraphicEdits.offset(origin.at, northM, eastM)
                threats.previewMove(origin.id, moved.lat, moved.lon)
            }
        }
    }

    /** The finger lifted at [at]: the thing is put down there. */
    fun end(at: LatLon) {
        val d = drag ?: return
        move(at)
        if (d.origin is Origin.OfThreat) threats.settle()
        finish()
    }

    /** The gesture was taken from us (a second finger, the system): the thing goes back where it was picked up from. */
    fun cancel() {
        val d = drag ?: return
        move(d.start)
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
