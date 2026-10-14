package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.PointWindDto
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.WindQuestion
import app.ezpztac.network.elevations
import app.ezpztac.network.routeWinds
import app.ezpztac.planning.PointWind
import app.ezpztac.planning.RouteElevations
import app.ezpztac.planning.RouteWinds
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import javax.inject.Inject

/** The two server calls route planning makes. [ApiClient] in the app; a stand-in in tests. */
public interface PlanningApi {
    public suspend fun elevations(points: List<LatLon>): List<Double?>
    public suspend fun routeWinds(points: List<WindQuestion>): Map<String, PointWindDto>
}

/** [PlanningApi] over the real client. */
public class ApiClientPlanningApi(private val client: ApiClient) : PlanningApi {
    override suspend fun elevations(points: List<LatLon>): List<Double?> = client.elevations(points)
    override suspend fun routeWinds(points: List<WindQuestion>): Map<String, PointWindDto> = client.routeWinds(points)
}

/** What fetching something for a route came to: a line for the person (what was found), or why not, in words. */
public sealed interface PlanningOutcome {
    public data class Done(val message: String) : PlanningOutcome

    public data class Failed(val message: String) : PlanningOutcome
}

/**
 * The two things a route can ask the server for: ground elevations (so altitudes can be read AGL and MSL, and true airspeed worked out at the density altitude), and the
 * wind at each point (the latest observation, or the forecast for a time ahead). Each is *fetched data*, not a planning decision: it lands in the route it was asked for
 * through [RouteSession.update], so it is not a step the person undoes, and it is applied to that route even if another set has been opened while the server was answering.
 *
 * **What was fetched for a place lands only on a point still there.** An answer takes seconds, and meanwhile the point can be moved (here, or by someone else in a mission
 * pack, whose move this device has taken by then) or taken away: the ground fetched for the place it left would measure its altitudes from the wrong ground, and in a pack it
 * would be sent to everyone. So each fetched value is kept only for a point of the route at the place it was asked for, in each version of the set (an undo step where the
 * point was elsewhere keeps what it had), and the person is told how many moved. A wind is the same: it is the nearest station's for that place and time, and once a plan
 * value nothing marks it as fetched, so one that landed on a point it was not asked for could never be told from one typed there, nor taken away when the point moves on.
 * **Not like the web**, which keeps whatever answers under the point's id.
 *
 * Both need a connection and both degrade: with none, the route is as it was and the person is told in words (never the server's own, which can name an internal type).
 * [zone] is the device's, for turning a wall-clock time into the instant the weather service is asked about.
 */
public class RoutePlanning @Inject constructor(
    private val api: PlanningApi,
    private val session: RouteSession,
) {
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    /** Fetches the wind at each named point of [routeId] in the open set [setId] and merges it into the route's plan. */
    public suspend fun fetchWinds(setId: String, routeId: String): PlanningOutcome {
        val route = session.active.value?.takeIf { it.id == setId }?.route(routeId) ?: return PlanningOutcome.Failed("That route is no longer open.")
        val request = RouteWinds.request(route) ?: return PlanningOutcome.Failed("The route has no named points.")
        val here = zone()
        val questions = request.points.map { WindQuestion(it.id, it.lat, it.lon, it.localTime?.let { time -> RouteWinds.instantText(time, here) }) }
        val found = try {
            api.routeWinds(questions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return PlanningOutcome.Failed(reason(e, "winds"))
        } catch (_: Exception) {
            return PlanningOutcome.Failed("The winds could not be fetched. Try again in a moment.")
        }
        if (found.isEmpty()) return PlanningOutcome.Done("No reporting stations were found near this route.")
        val winds = found.mapValues { (_, w) -> PointWind(w.dirTrue.toDouble(), w.speedKts, w.tempC) }
        val asked = askedAt(request.amps)
        val moved = movedSince(setId, routeId, asked).intersect(found.keys)
        if (moved.size == found.size) return PlanningOutcome.Failed(everyPointMoved("winds"))
        val applied = session.update(setId) { set -> set.mapRoute(routeId) { it.copy(plan = RouteWinds.merge(it.plan, stillAt(it, asked), winds)) } }
        if (!applied) return PlanningOutcome.Failed("That route is no longer here.")
        val landed = found.filterKeys { it !in moved }
        val sources = landed.values.groupingBy { it.source }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        val line = "${landed.size} ${if (landed.size == 1) "point" else "points"} · $sources"
        return PlanningOutcome.Done(if (moved.isEmpty()) line else "$line.${movedWords(moved.size)}")
    }

    /** Fetches the ground elevation at each named point of [routeId] and keeps the ones the server could read with the route. */
    public suspend fun fetchElevations(setId: String, routeId: String): PlanningOutcome {
        val route = session.active.value?.takeIf { it.id == setId }?.route(routeId) ?: return PlanningOutcome.Failed("That route is no longer open.")
        val amps = RouteElevations.points(route)
        if (amps.isEmpty()) return PlanningOutcome.Failed("The route has no named points.")
        val feet = try {
            api.elevations(amps.map { LatLon(it.lat, it.lon) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return PlanningOutcome.Failed(reason(e, "elevations"))
        } catch (_: Exception) {
            return PlanningOutcome.Failed("The elevations could not be fetched. Try again in a moment.")
        }
        val found = RouteElevations.byPoint(amps, feet)
        if (found.isEmpty()) return PlanningOutcome.Failed("The elevation service could not read the ground here.")
        val asked = askedAt(amps)
        val moved = movedSince(setId, routeId, asked).intersect(found.keys)
        if (moved.size == found.size) return PlanningOutcome.Failed(everyPointMoved("ground elevations"))
        val applied = session.update(setId) { set ->
            set.mapRoute(routeId) { route ->
                val here = stillAt(route, asked).mapNotNullTo(HashSet()) { it.id }
                val kept = found.filterKeys { it in here }
                if (kept.isEmpty()) route else route.copy(elevations = route.elevations + kept)
            }
        }
        if (!applied) return PlanningOutcome.Failed("That route is no longer here.")
        val landed = found.size - moved.size
        val line = if (landed == amps.size) "Ground elevations for $landed points." else "Ground elevations for $landed of ${amps.size} points."
        return PlanningOutcome.Done(line + movedWords(moved.size))
    }

    // Each named point asked about, by id, and the place it was asked for.
    private fun askedAt(points: List<RoutePoint>): Map<String, LatLon> = points.mapNotNull { p -> p.id?.let { it to LatLon(p.lat, p.lon) } }.toMap()

    // The points of [route] still at the place they were asked about ([asked]), in the route's order: what fetched data may land on.
    private fun stillAt(route: SketchRoute, asked: Map<String, LatLon>): List<RoutePoint> =
        route.points.filter { p -> p.id?.let(asked::get)?.let { it.lat == p.lat && it.lon == p.lon } == true }

    // The points asked about that the set showing now has elsewhere, or not at all: moved (here, or by someone else in a mission pack) or taken away since they were
    // asked about. None when another set shows by now: the route is changed as its stored record has it, and what moved there is not said.
    private fun movedSince(setId: String, routeId: String, asked: Map<String, LatLon>): Set<String> {
        val route = session.active.value?.takeIf { it.id == setId }?.route(routeId) ?: return emptySet()
        return asked.keys - stillAt(route, asked).mapNotNullTo(HashSet()) { it.id }
    }

    // What the person is told of points that moved, or went, while they were fetched for.
    private fun movedWords(count: Int): String = when (count) {
        0 -> ""
        1 -> " One point moved or went while they were fetched, so it has none from this fetch."
        else -> " $count points moved or went while they were fetched, so they have none from this fetch."
    }

    private fun everyPointMoved(what: String): String = "Every point moved or went while the $what were fetched, so none were kept. Fetch them again."

    private fun reason(e: ApiException, what: String): String = when {
        e is NetworkException -> "There is no connection to the server, so the $what could not be fetched."
        e is SessionEndedException -> "Sign in again to fetch the $what."
        e is RateLimitedException -> "Too many requests. Wait a moment and try again."
        else -> "The $what could not be fetched. Try again in a moment."
    }
}
