package app.ezpztac.map

import app.ezpztac.model.LatLon
import kotlin.math.hypot

/** Picks the nearest visible threat marker within a finger's reach. Later markers win a tie because they are drawn over earlier ones. */
object ThreatHitTest {
    fun pick(scene: ThreatScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): String? {
        val finger = view.toScreen(tap)
        var best: String? = null
        var bestDistance = Double.MAX_VALUE
        for (pin in scene.pins) {
            val at = view.toScreen(pin.at)
            val distance = hypot(finger.x - at.x, finger.y - at.y)
            if (distance <= touchRadiusPx && distance <= bestDistance) {
                best = pin.id
                bestDistance = distance
            }
        }
        return best
    }
}
