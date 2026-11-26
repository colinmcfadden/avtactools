package app.ezpztac.map

import app.ezpztac.model.DragTarget
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon

/** Which part of a backstop a finger is on: the end of its arrow, the arrow itself, or the dashed line (which is only held: it stays where it was sketched). */
enum class BackstopPart { TIP, ARROW, LINE }

/** What a finger on the map found among the measures. */
sealed interface MeasureHit {
    data class Backstop(val id: String, val part: BackstopPart) : MeasureHit

    /** A FLOT or a phase line, and the point of it under the finger, if one is. */
    data class Line(val ref: LineRef, val vertex: Int?) : MeasureHit
}

/**
 * Which backstop, FLOT or phase line a finger is on, as plain geometry over [MapProjection], so it is tested without a GPU. A point (the end of an arrow, a point of a line)
 * is hit within the touch radius of it and wins over a line within reach, so the handle a person aims at is the one they get; of two of a kind the nearer wins. A line is hit
 * within the radius of any of its legs (a FLOT's scallops bulge half their width to one side, well inside it).
 */
object MeasuresHitTest {
    fun pick(scene: MeasuresScene, view: MapProjection, tap: LatLon, touchRadiusPx: Double): MeasureHit? {
        if (scene.backstops.isEmpty() && scene.lines.isEmpty()) return null
        val finger = view.toScreen(tap)
        var bestPoint: MeasureHit? = null
        var bestPointDistance = Double.POSITIVE_INFINITY
        var bestLine: MeasureHit? = null
        var bestLineDistance = Double.POSITIVE_INFINITY
        fun point(hit: MeasureHit, distance: Double) {
            if (distance <= touchRadiusPx && distance < bestPointDistance) {
                bestPoint = hit
                bestPointDistance = distance
            }
        }
        fun line(hit: MeasureHit, distance: Double) {
            if (distance <= touchRadiusPx && distance < bestLineDistance) {
                bestLine = hit
                bestLineDistance = distance
            }
        }
        scene.backstops.forEach { backstop ->
            val arrow = MeasuresOnScreen.arrow(backstop, scene.target, view)
            if (arrow != null) {
                point(MeasureHit.Backstop(backstop.id, BackstopPart.TIP), distance(finger, arrow.head))
                line(MeasureHit.Backstop(backstop.id, BackstopPart.ARROW), GraphicHitTest.distanceToSegment(finger, screen(arrow.tail), screen(arrow.head)))
            }
            line(MeasureHit.Backstop(backstop.id, BackstopPart.LINE), toPolyline(finger, backstop.line.map(view::toScreen)))
        }
        scene.lines.forEach { drawn ->
            val pixels = drawn.points.map(view::toScreen)
            pixels.forEachIndexed { i, p -> point(MeasureHit.Line(drawn.ref, i), distance(finger, p)) }
            line(MeasureHit.Line(drawn.ref, null), toPolyline(finger, pixels))
        }
        return bestPoint ?: bestLine
    }

    /**
     * What a long press there picks up: the end of a backstop's arrow, the arrow whole, or a point of a line; null for a backstop's dashed line or a leg of a line, which are
     * only held. Each is what [hit] says, on something that may be moved; a read-only pack's is never picked up.
     */
    fun dragTarget(scene: MeasuresScene, hit: MeasureHit): DragTarget? = when (hit) {
        is MeasureHit.Backstop -> {
            val ref = GraphicRef(BACKSTOPS, hit.id)
            val movable = scene.backstops.firstOrNull { it.id == hit.id }?.movable == true
            when {
                !movable -> null
                hit.part == BackstopPart.TIP -> DragTarget.BackstopTip(ref)
                hit.part == BackstopPart.ARROW -> DragTarget.BackstopArrow(ref)
                else -> null
            }
        }
        is MeasureHit.Line -> {
            val movable = scene.lines.firstOrNull { it.ref == hit.ref }?.movable == true
            hit.vertex?.takeIf { movable }?.let { DragTarget.LineVertex(hit.ref.setId, hit.ref.key, hit.ref.id, it) }
        }
    }

    const val BACKSTOPS = "backstops"

    private fun screen(p: app.ezpztac.planning.Pixel) = ScreenPoint(p.x, p.y)

    private fun distance(a: ScreenPoint, b: app.ezpztac.planning.Pixel) = Math.hypot(a.x - b.x, a.y - b.y)

    private fun distance(a: ScreenPoint, b: ScreenPoint) = Math.hypot(a.x - b.x, a.y - b.y)

    private fun toPolyline(finger: ScreenPoint, pixels: List<ScreenPoint>): Double = when (pixels.size) {
        0 -> Double.POSITIVE_INFINITY
        1 -> distance(finger, pixels[0])
        else -> (1 until pixels.size).minOf { GraphicHitTest.distanceToSegment(finger, pixels[it - 1], pixels[it]) }
    }
}
