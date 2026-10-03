package app.ezpztac.map

import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Which planning graphic a tap on the map was on, worked out on the screen with [MapProjection] and so tried without a GPU.
 *
 * The rules, which are what a person with a finger the size of a fingertip needs:
 * - Anything with a point (an aircraft, a PZ marker's anchor, tip or arrow, a go-around) is hit within [touchRadiusPx] of that point, and an
 *   aircraft is hit anywhere on its rotor disc, since that is what is drawn. The nearest wins, so two aircraft whose discs overlap go to the
 *   one whose middle is closer to the finger.
 * - A sector is hit anywhere inside it, but only when nothing with a point was: a big sector must not swallow a tap meant for an aircraft
 *   standing in it. Where sectors overlap the smaller one wins.
 * - Otherwise nothing: the caller puts the held graphic down.
 */
object GraphicHitTest {
    fun pick(graphics: GraphicsScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): GraphicRef? {
        val finger = view.toScreen(tap)
        var best: GraphicRef? = null
        var bestDistance = Double.MAX_VALUE
        fun consider(ref: GraphicRef, distance: Double) {
            if (distance < bestDistance) {
                best = ref
                bestDistance = distance
            }
        }

        for (aircraft in graphics.aircraft) {
            val distance = distance(finger, view.toScreen(aircraft.at))
            val reach = max(touchRadiusPx, view.metersToPixels(aircraft.diameterM / 2, aircraft.at.lat))
            if (distance <= reach) consider(aircraft.ref, distance)
        }
        for (pz in graphics.pzMarkers) {
            val distance = distanceToSegment(finger, view.toScreen(pz.anchor), view.toScreen(pz.tip))
            if (distance <= touchRadiusPx) consider(pz.ref, distance)
        }
        for (goAround in graphics.goArounds) {
            val distance = distance(finger, view.toScreen(goAround.at))
            if (distance <= touchRadiusPx) consider(goAround.ref, distance)
        }
        best?.let { return it }

        var smallest = Double.MAX_VALUE
        for (sector in graphics.sectors) {
            val ring = sector.ring.map(view::toScreen)
            if (!contains(ring, finger)) continue
            val area = abs(area(ring))
            if (area < smallest) {
                smallest = area
                best = sector.ref
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

    /** Ray casting: a point is inside when a ray from it crosses the ring an odd number of times. */
    private fun contains(ring: List<ScreenPoint>, p: ScreenPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** The shoelace area of a ring, signed. */
    private fun area(ring: List<ScreenPoint>): Double {
        var sum = 0.0
        var j = ring.lastIndex
        for (i in ring.indices) {
            sum += (ring[j].x + ring[i].x) * (ring[j].y - ring[i].y)
            j = i
        }
        return sum / 2
    }
}
