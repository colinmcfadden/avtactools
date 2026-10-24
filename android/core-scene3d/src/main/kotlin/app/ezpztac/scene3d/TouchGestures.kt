package app.ezpztac.scene3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** One of the camera's moves, as [TouchGestures] reads the fingers. */
public sealed interface CameraGesture {
    /** One finger dragged the ground from [from] to [to]. */
    public data class Pan(val from: ScreenPoint, val to: ScreenPoint) : CameraGesture

    /** A pinch at [at] grew by [factor] (more than 1: the fingers moved apart, toward the place between them). */
    public data class Zoom(val at: ScreenPoint, val factor: Double) : CameraGesture

    /** A twist by [angle] radians, clockwise on the screen. */
    public data class Turn(val angle: Double) : CameraGesture

    /** Two fingers moved together up the view by [by] radians' worth ([TouchGestures.TILT_PER_HEIGHT] for the view's height, as Cesium's). */
    public data class Tilt(val by: Double) : CameraGesture
}

/**
 * The fingers on the 3D view read as the camera's moves ([CameraMoves]): one finger pans once it has moved past [slopPx]; two fingers either
 * tilt, when both move up or down together side by side, or pinch and twist, what is between them dragged as one finger would be; which of the
 * two is decided by the first movement past the slop and kept until a finger lifts. A finger put down or lifted starts again from where the
 * fingers are, so nothing jumps, and the finger left of a pinch goes on panning. Plain: the view hands it every finger down (by pointer id, in
 * its pixels) each time any moves, and applies what comes back.
 */
public class TouchGestures(private val slopPx: Double) {
    private enum class Mode { NONE, PAN, UNDECIDED, TILT, PINCH }

    private var mode = Mode.NONE
    private var last: Map<Int, ScreenPoint> = emptyMap()
    private var start: Map<Int, ScreenPoint> = emptyMap()

    /** The fingers now, every one down; [viewHeightPx] for a tilt's measure. What the camera should do for the change since they were last given. */
    public fun fingers(now: Map<Int, ScreenPoint>, viewHeightPx: Double): List<CameraGesture> {
        if (now.keys != last.keys) {
            last = now
            start = now
            mode = when (now.size) {
                0 -> Mode.NONE
                // The finger left of a pinch or a pan goes on panning; one left of two that had not moved starts as a touch does.
                1 -> if (mode == Mode.PAN || mode == Mode.PINCH || mode == Mode.TILT) Mode.PAN else Mode.NONE
                else -> Mode.UNDECIDED
            }
            return emptyList()
        }
        val before = last
        last = now
        return when (now.size) {
            1 -> one(before.values.first(), now.values.first())
            2 -> two(before, now, viewHeightPx)
            else -> emptyList()                                                          // three or more: held still
        }
    }

    /** Every finger up: the next touch starts afresh. */
    public fun lifted() {
        mode = Mode.NONE
        last = emptyMap()
        start = emptyMap()
    }

    private fun one(before: ScreenPoint, now: ScreenPoint): List<CameraGesture> {
        if (mode == Mode.NONE) {
            val from = start.values.first()
            if (hypot(now.x - from.x, now.y - from.y) < slopPx) return emptyList()
            mode = Mode.PAN
            // The first move takes up the slop, so the ground under the finger is the ground it went down on.
            return listOf(CameraGesture.Pan(from, now))
        }
        return listOf(CameraGesture.Pan(before, now))
    }

    private fun two(before: Map<Int, ScreenPoint>, now: Map<Int, ScreenPoint>, viewHeightPx: Double): List<CameraGesture> {
        val ids = now.keys.sorted()
        val (a1, b1) = ids.map { now.getValue(it) }
        if (mode == Mode.UNDECIDED) {
            val (a0, b0) = ids.map { start.getValue(it) }
            mode = decide(a0, b0, a1, b1) ?: return emptyList()
            // What moved before the decision is taken up now, from where the fingers went down.
            return gestures(a0, b0, a1, b1, viewHeightPx)
        }
        val (a0, b0) = ids.map { before.getValue(it) }
        return gestures(a0, b0, a1, b1, viewHeightPx)
    }

    // A tilt: both fingers moving up or down together, each more up and down than across, the line between them nearer level than upright.
    private fun decide(a0: ScreenPoint, b0: ScreenPoint, a1: ScreenPoint, b1: ScreenPoint): Mode? {
        val ady = a1.y - a0.y
        val bdy = b1.y - b0.y
        val moved = maxOf(hypot(a1.x - a0.x, ady), hypot(b1.x - b0.x, bdy))
        val spanChange = abs(span(a1, b1) - span(a0, b0))
        val twist = abs(angleChange(a0, b0, a1, b1)) * span(a1, b1) / 2
        if (maxOf(moved, spanChange, twist) < slopPx) return null
        val together = ady * bdy > 0 && abs(ady) > abs(a1.x - a0.x) && abs(bdy) > abs(b1.x - b0.x)
        val sideBySide = abs(b1.y - a1.y) < abs(b1.x - a1.x)
        return if (together && sideBySide && spanChange < slopPx && twist < slopPx) Mode.TILT else Mode.PINCH
    }

    private fun gestures(a0: ScreenPoint, b0: ScreenPoint, a1: ScreenPoint, b1: ScreenPoint, viewHeightPx: Double): List<CameraGesture> = when (mode) {
        Mode.TILT -> {
            // Up the view is toward the horizon: the fingers' mean rise over the view's height, at Cesium's rate.
            val rise = -((a1.y - a0.y) + (b1.y - b0.y)) / 2
            listOf(CameraGesture.Tilt(rise / viewHeightPx * TILT_PER_HEIGHT))
        }
        Mode.PINCH -> buildList {
            val m0 = ScreenPoint((a0.x + b0.x) / 2, (a0.y + b0.y) / 2)
            val m1 = ScreenPoint((a1.x + b1.x) / 2, (a1.y + b1.y) / 2)
            if (m0 != m1) add(CameraGesture.Pan(m0, m1))
            val s0 = span(a0, b0)
            val s1 = span(a1, b1)
            if (s0 > 0 && s1 > 0 && s1 != s0) add(CameraGesture.Zoom(m1, s1 / s0))
            val angle = angleChange(a0, b0, a1, b1)
            if (angle != 0.0) add(CameraGesture.Turn(angle))
        }
        else -> emptyList()
    }

    private fun span(a: ScreenPoint, b: ScreenPoint) = hypot(b.x - a.x, b.y - a.y)

    public companion object {
        /**
         * How far a tilt turns for two fingers moved up the whole view, radians: Cesium's, whose pinch reads the fingers' height at a quarter
         * (`ScreenSpaceEventHandler`: `cY = (y1 + y2) × 0.125`) and whose tilt turns by its fastest rotate rate, 1.77, times π for the view's
         * height (`rotate3D`, the rate's fastest at any distance past three metres): about 0.44π.
         */
        public const val TILT_PER_HEIGHT: Double = 1.77 / 4 * PI
    }

    // Clockwise on the screen is positive: its y runs down.
    private fun angleChange(a0: ScreenPoint, b0: ScreenPoint, a1: ScreenPoint, b1: ScreenPoint): Double {
        val d = atan2(b1.y - a1.y, b1.x - a1.x) - atan2(b0.y - a0.y, b0.x - a0.x)
        return ((d % (2 * PI)) + 3 * PI) % (2 * PI) - PI
    }
}
