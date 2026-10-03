package app.ezpztac.map

import app.ezpztac.model.LatLon
import kotlin.math.hypot

/** What a tap on the map was on: a local point, and the set it is in. */
data class PointHit(val setId: String, val pointId: String)

/**
 * Which local point a tap on the map was on, worked out on the screen with [MapProjection] and so tried without a GPU: the nearest within
 * [touchRadiusPx], or none. Of two on one place the later in the scene wins, since it is drawn over the earlier.
 */
object PointHitTest {
    fun pick(scene: PointScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): PointHit? {
        val finger = view.toScreen(tap)
        var best: PointHit? = null
        var bestDistance = Double.MAX_VALUE
        for (pin in scene.pins) {
            val at = view.toScreen(pin.at)
            val distance = hypot(finger.x - at.x, finger.y - at.y)
            if (distance <= touchRadiusPx && distance <= bestDistance) {
                best = PointHit(pin.setId, pin.pointId)
                bestDistance = distance
            }
        }
        return best
    }
}
