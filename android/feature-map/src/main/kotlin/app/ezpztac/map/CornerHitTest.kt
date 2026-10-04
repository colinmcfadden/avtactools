package app.ezpztac.map

import app.ezpztac.model.BoundaryCornerRef
import app.ezpztac.model.LatLon
import kotlin.math.hypot

/**
 * Which corner of the boundary a tap was on, worked out on the screen with [MapProjection] and so tried without a GPU: the nearest corner of the analysis boundary or of the drawn
 * one (a polygon of three corners or more) within [touchRadiusPx], or nothing. Corners are the lowest thing on the map, so this is looked at after everything else.
 */
object CornerHitTest {
    fun pick(scene: LzScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): BoundaryCornerRef? {
        val finger = view.toScreen(tap)
        var best: BoundaryCornerRef? = null
        var bestDistance = Double.MAX_VALUE
        fun consider(points: List<LatLon>, drawn: Boolean) {
            if (points.size < 3) return
            points.forEachIndexed { i, p ->
                val at = view.toScreen(p)
                val distance = hypot(finger.x - at.x, finger.y - at.y)
                if (distance <= touchRadiusPx && distance < bestDistance) {
                    best = BoundaryCornerRef(drawn, i)
                    bestDistance = distance
                }
            }
        }
        consider(scene.boundary, drawn = false)
        consider(scene.drawn, drawn = true)
        return best
    }
}
