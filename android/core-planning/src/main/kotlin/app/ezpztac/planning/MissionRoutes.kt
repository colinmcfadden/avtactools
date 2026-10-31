package app.ezpztac.planning

import app.ezpztac.geo.GreatCircle
import app.ezpztac.model.LatLon
import app.ezpztac.model.Mission
import app.ezpztac.model.MissionRoute
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.SketchRoute
import app.ezpztac.model.Units
import kotlin.math.max

/**
 * Bringing the routes of an AMPS mission into the app's own route sets.
 *
 * The routes become **sketched routes**, a copy: the points, the plan the mission recorded (altitudes, airspeeds, winds, clock times) and the ground
 * elevations come across, and the file itself is not kept. What only AMPS knows (the vehicle model, anything in the mission that is not a route) stays in the
 * file, and exporting a set builds a new mission from its routes. The web keeps an imported mission as the file and writes edits back into it; that round trip is a
 * separate, larger piece of work, and this is not a pretence of it.
 */
public object MissionRoutes {
    /** Where a set of routes lies: its middle and how far across it is, for taking the map there. */
    public data class Extent(val center: LatLon, val spanMeters: Double)

    /**
     * The mission's routes as sketched routes, each in the first palette colour not already taken (by [takenColors] or by one made here). A route with no
     * points has nothing to draw and is left out; a blank name becomes `ROUTE n` by its place in the mission. [newId] names each route (the web's `sketch-…`).
     */
    public fun toSketchRoutes(mission: Mission, newId: () -> String, takenColors: Collection<String> = emptyList()): List<SketchRoute> {
        val taken = ArrayList(takenColors)
        val routes = ArrayList<SketchRoute>()
        mission.routes.forEachIndexed { index, route ->
            if (route.points.isEmpty()) return@forEachIndexed
            val color = RouteColors.next(taken)
            taken += color
            routes += SketchRoute(
                id = newId(),
                name = route.name.trim().ifEmpty { "ROUTE ${index + 1}" },
                color = color,
                points = route.points,
                plan = route.plan,
                elevations = route.elevations,
            )
        }
        return routes
    }

    /**
     * The routes of a **saved mission**, which keeps the file: every route, in the file's order, with its segment, because a change to one is found in the file by its place
     * and its segment (so a route with no points is kept, though there is nothing to draw). A colour is the one the mission's summary kept for that place, else the first of the
     * palette not taken. A blank name is `ROUTE n`.
     */
    public fun toMissionSketchRoutes(mission: Mission, colors: List<String?>, newId: () -> String): List<SketchRoute> {
        val taken = ArrayList<String>()
        return mission.routes.mapIndexed { index, route ->
            val color = colors.getOrNull(index) ?: RouteColors.next(taken)
            taken += color
            SketchRoute(
                id = newId(), name = route.name.trim().ifEmpty { "ROUTE ${index + 1}" }, color = color, points = route.points, plan = route.plan,
                elevations = route.elevations, segmentId = route.segmentId,
            )
        }
    }

    /** What a mission's file is told about its routes: each route as it stands. The name is the file's own and is not written back. */
    public fun toMissionRoutes(routes: List<SketchRoute>): List<MissionRoute> =
        routes.map { MissionRoute(it.name, it.segmentId, it.points, it.plan, it.elevations) }

    /** True when two lists of routes would write the same file: a name, a colour or a hidden route is not in it. */
    public fun sameFileContent(a: List<MissionRoute>, b: List<MissionRoute>): Boolean =
        a.size == b.size && a.indices.all { i ->
            a[i].segmentId == b[i].segmentId && a[i].points == b[i].points && a[i].plan == b[i].plan && a[i].elevations == b[i].elevations
        }

    /** How many designated (named) route points the routes have in all, which is what a crew counts: shaping points only bend the line. */
    public fun namedPoints(routes: List<SketchRoute>): Int = routes.sumOf { r -> r.points.count { it.kind == RoutePoint.KIND_AMPS } }

    /** The middle of every point of [routes] and the longer side of the box round them, or null when there is no point. */
    public fun extentOf(routes: List<SketchRoute>): Extent? {
        val points = routes.flatMap { it.points }
        if (points.isEmpty()) return null
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val north = GreatCircle.distanceFeet(minLat, minLon, maxLat, minLon)
        val east = GreatCircle.distanceFeet((minLat + maxLat) / 2, minLon, (minLat + maxLat) / 2, maxLon)
        return Extent(LatLon((minLat + maxLat) / 2, (minLon + maxLon) / 2), max(north, east) / Units.METERS_TO_FEET)
    }
}
