package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.planning.DraftPoint
import app.ezpztac.planning.Designation
import app.ezpztac.planning.RouteColors
import app.ezpztac.planning.SketchOps
import app.ezpztac.model.RoutePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A route being drawn on the open set [setId]: the points put down so far, in order, each with what the person said it is, if anything. */
data class RouteDraft(val setId: String, val points: List<DraftPoint>) {
    val canFinish: Boolean get() = points.size >= RouteSketching.MIN_POINTS
}

/** What finishing a drawn route came to: the route it made, or why not, in words. */
sealed interface SketchFinish {
    data class Done(val routeId: String) : SketchFinish
    data class Refused(val reason: String) : SketchFinish
}

/** Names for what drawing makes up, so a test can say what they are. */
internal interface RouteIds {
    /** A route's id: the web's `sketch-<millis>-<random>`, whose prefix is how the web tells a sketch from an imported route. */
    fun route(): String

    /** A point's id: a uuid, as the web makes them. */
    fun point(): String

    companion object {
        val Random: RouteIds = object : RouteIds {
            override fun route(): String = "sketch-${System.currentTimeMillis()}-${UUID.randomUUID().toString().replace("-", "").take(8)}"
            override fun point(): String = UUID.randomUUID().toString()
        }
    }
}

/**
 * Drawing a route by hand, which the web does in its sketch mode (`useRouteSketch`): the person puts down the points one by one and the finished
 * line becomes a route of the open set, planned for the mission aircraft ([AircraftProfiles.active]) with the colour and the standard attack-profile
 * designations the web would give it ([SketchOps.build]).
 *
 * The points are a draft held here, never in the set, until the person finishes: abandoning a half-drawn route leaves no trace. The draft belongs
 * to one set and is dropped when another is opened ([cancel]). Finishing is one step of the set's own undo.
 */
@Singleton
class RouteSketching @Inject constructor(
    private val session: RouteSession,
    private val aircraft: AircraftProfiles,
) {
    internal var ids: RouteIds = RouteIds.Random

    private val _draft = MutableStateFlow<RouteDraft?>(null)

    /** The route being drawn, or null when nothing is. */
    val draft: StateFlow<RouteDraft?> = _draft.asStateFlow()

    /** Starts a draft on the open set. Null when drawing has started, or why not in words. */
    fun start(): String? {
        val open = session.active.value ?: return "Open a set of routes first."
        if (_draft.value?.setId == open.id) return null                                 // already drawing here: the points so far stay
        _draft.value = RouteDraft(open.id, emptyList())
        return null
    }

    /** Puts a point down at [at]. False when nothing is being drawn on the open set, or [at] is not a position. */
    fun addPoint(at: LatLon, designation: Designation? = null): Boolean {
        val current = _draft.value ?: return false
        if (session.active.value?.id != current.setId) return false
        if (at.lat !in -90.0..90.0 || at.lon !in -180.0..180.0) return false            // NaN is in no range, so it is refused here too
        _draft.value = current.copy(points = current.points + DraftPoint(at.lat, at.lon, designation))
        return true
    }

    /** Takes the last point back. */
    fun removeLastPoint() {
        val current = _draft.value ?: return
        _draft.value = current.copy(points = current.points.dropLast(1))
    }

    /** Stops drawing and forgets the points. */
    fun cancel() {
        _draft.value = null
    }

    /**
     * Makes the draft a route of the open set and stops drawing. A refusal says why and drawing goes on (a route needs [MIN_POINTS] points). [name] is
     * what the person called it, or blank for `ROUTE n`, upper-cased as the web does.
     */
    fun finish(name: String = ""): SketchFinish {
        val current = _draft.value ?: return SketchFinish.Refused("Nothing is being drawn.")
        if (!current.canFinish) return SketchFinish.Refused("A route needs at least $MIN_POINTS points.")
        val open = session.active.value
        if (open == null || open.id != current.setId) {
            _draft.value = null
            return SketchFinish.Refused("The set this was drawn on is no longer open.")
        }
        val route = SketchOps.build(
            draft = current.points,
            name = name.trim().uppercase().ifEmpty { "ROUTE ${open.routes.size + 1}" },
            id = ids.route(),
            color = RouteColors.next(open.routes.map { it.color }),
            plan = RoutePlan.forAircraft(aircraft.active.value),
            newPointId = ids::point,
        ) ?: return SketchFinish.Refused("A route needs at least $MIN_POINTS points.")
        session.edit("Draw route") { it.plus(route) }
        _draft.value = null
        return SketchFinish.Done(route.id)
    }

    companion object {
        /** A route is a line: the web makes one from two points. */
        const val MIN_POINTS = 2
    }
}
