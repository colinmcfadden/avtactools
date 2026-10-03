package app.ezpztac.map

import kotlin.math.hypot
import app.ezpztac.model.LatLon

/** What a tap on the map was on: a route, and the point of it if the finger was on one. */
data class RouteHit(val routeId: String, val pointId: String?)

/**
 * Which route, and which point of it, a tap on the map was on, worked out on the screen with [MapProjection] and so tried without a GPU.
 * - A point is hit within [touchRadiusPx] of it, and the nearest wins; a named point beats a shaping point at the same distance, since it is drawn over it.
 *   Only what is drawn can be hit: the shaping points of a route that is not being worked on are not on the map.
 * - Otherwise the line is hit within [touchRadiusPx] of any of its legs, nearest first, which picks the route and no point.
 * - Otherwise nothing: the caller puts what is held down.
 * A point with no id cannot be held, so touching one picks its route.
 */
object RouteHitTest {
    fun pick(scene: RouteScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): RouteHit? {
        val finger = view.toScreen(tap)
        var best: RouteHit? = null
        var bestDistance = Double.MAX_VALUE
        var bestIsPin = false
        for (route in scene.routes) {
            for (pin in route.pins) {
                if (!pin.amps && !route.selected) continue
                val distance = distance(finger, view.toScreen(pin.at))
                if (distance > touchRadiusPx) continue
                if (distance < bestDistance || (distance == bestDistance && pin.amps && !bestIsPin)) {
                    best = RouteHit(route.id, pin.id)
                    bestDistance = distance
                    bestIsPin = pin.amps
                }
            }
        }
        best?.let { return it }

        for (route in scene.routes) {
            val line = route.line.map(view::toScreen)
            for (i in 0 until line.size - 1) {
                val distance = distanceToSegment(finger, line[i], line[i + 1])
                if (distance <= touchRadiusPx && distance < bestDistance) {
                    best = RouteHit(route.id, null)
                    bestDistance = distance
                }
            }
        }
        return best
    }

    private fun distance(a: ScreenPoint, b: ScreenPoint) = hypot(a.x - b.x, a.y - b.y)

    /** The distance from [p] to the nearest point of the segment [a]–[b] (to [a] when the two are the same point). */
    private fun distanceToSegment(p: ScreenPoint, a: ScreenPoint, b: ScreenPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0.0) return distance(p, a)
        val along = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return distance(p, ScreenPoint(a.x + along * dx, a.y + along * dy))
    }
}
