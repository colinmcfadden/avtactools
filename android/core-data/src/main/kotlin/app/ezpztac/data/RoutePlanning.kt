package app.ezpztac.data

import app.ezpztac.model.LatLon
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
        val applied = session.update(setId) { set -> set.mapRoute(routeId) { it.copy(plan = RouteWinds.merge(it.plan, request.amps, winds)) } }
        if (!applied) return PlanningOutcome.Failed("That route is no longer here.")
        val sources = found.values.groupingBy { it.source }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        return PlanningOutcome.Done("${found.size} ${if (found.size == 1) "point" else "points"} · $sources")
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
        val applied = session.update(setId) { set -> set.mapRoute(routeId) { it.copy(elevations = it.elevations + found) } }
        if (!applied) return PlanningOutcome.Failed("That route is no longer here.")
        return PlanningOutcome.Done(if (found.size == amps.size) "Ground elevations for ${found.size} points." else "Ground elevations for ${found.size} of ${amps.size} points.")
    }

    private fun reason(e: ApiException, what: String): String = when {
        e is NetworkException -> "There is no connection to the server, so the $what could not be fetched."
        e is SessionEndedException -> "Sign in again to fetch the $what."
        e is RateLimitedException -> "Too many requests. Wait a moment and try again."
        else -> "The $what could not be fetched. Try again in a moment."
    }
}
