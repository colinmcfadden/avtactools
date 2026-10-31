package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.RouteSet
import app.ezpztac.planning.SketchOps
import kotlinx.coroutines.CoroutineScope

/**
 * The set of routes that is open: held in memory while its routes are drawn and planned, written back to the database after a pause, and undoable
 * ([DocumentSession] says how, and why the wait). Nothing in a route set follows from anything else, so there is nothing to tidy after a change.
 *
 * A mission pack's route set opens here too, under an id naming the pack and the item ([app.ezpztac.missionpacks.PackRef]), so every route tool works
 * on it as on any set; it is read from and written to [packs] ([PackItemStore]), never the library, and closing it lets go of nothing the library
 * holds (an open mission's file).
 */
class RouteSession(
    repository: RouteRepository,
    scope: CoroutineScope,
    /** Where a mission pack's route sets are read and written. None: an id naming a pack item opens nothing. */
    packs: DocumentStore<RouteSet>? = null,
) : DocumentSession<RouteSet>(
    store = PackRoutedStore(
        library = object : DocumentStore<RouteSet> {
            override suspend fun open(uuid: String): RouteSet? = repository.open(uuid)
            override suspend fun save(document: RouteSet): RouteSet = repository.save(document)
            override fun release(uuid: String) = repository.release()
        },
        packs = packs,
        idOf = { it.id },
    ),
    scope = scope,
    idOf = { it.id },
    reidentify = { set, saved -> set.copy(id = saved.id, savedId = saved.savedId) },
)

/**
 * The set with point [pointId] of route [routeId] moved to where [to] puts it (given where it is), as a person moves one: the Routes panel's nudge, the
 * crosshair and a typed grid, and a drag on the map ([MapDrag]). It forgets a charted elevation the point had snapped to ([SketchOps.move]) and the ground
 * elevation fetched for the old place, which is not this place's (altitudes AGL would be measured from the wrong ground): the web keeps the fetched one;
 * here it is dropped until the next fetch, and a mission pack is sent it as null. The same set when the route has no such point.
 */
fun RouteSet.withPointMoved(routeId: String, pointId: String, to: (LatLon) -> LatLon): RouteSet = mapRoute(routeId) { route ->
    val point = route.points.firstOrNull { it.id == pointId } ?: return@mapRoute route
    val at = to(LatLon(point.lat, point.lon))
    val moved = SketchOps.move(route, pointId, at.lat, at.lon, null)
    if (pointId in moved.elevations) moved.copy(elevations = moved.elevations - pointId) else moved
}
