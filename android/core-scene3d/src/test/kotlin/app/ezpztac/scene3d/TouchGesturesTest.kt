package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

/** The fingers read as the camera's moves: slop, panning, a pinch and a twist, a tilt, and what changes when a finger comes or goes. */
class TouchGesturesTest {
    private val height = 2000.0

    private fun p(x: Double, y: Double) = ScreenPoint(x, y)

    @Test
    fun `one finger pans only once it has moved past the slop, and then from where it went down`() {
        val g = TouchGestures(slopPx = 20.0)
        assertTrue(g.fingers(mapOf(1 to p(100.0, 100.0)), height).isEmpty())
        assertTrue(g.fingers(mapOf(1 to p(110.0, 105.0)), height).isEmpty())
        assertEquals(listOf(CameraGesture.Pan(p(100.0, 100.0), p(130.0, 100.0))), g.fingers(mapOf(1 to p(130.0, 100.0)), height))
        assertEquals(listOf(CameraGesture.Pan(p(130.0, 100.0), p(150.0, 120.0))), g.fingers(mapOf(1 to p(150.0, 120.0)), height))
    }

    @Test
    fun `two fingers spreading and turning pinch and twist, dragging what is between them`() {
        val g = TouchGestures(slopPx = 20.0)
        g.fingers(mapOf(1 to p(400.0, 1000.0), 2 to p(600.0, 1000.0)), height)
        // Spread to twice as far, turned a quarter clockwise about their middle, the middle moved down by 50.
        val moves = g.fingers(mapOf(1 to p(500.0, 850.0), 2 to p(500.0, 1250.0)), height)
        val pan = moves.filterIsInstance<CameraGesture.Pan>().single()
        assertEquals(p(500.0, 1000.0), pan.from)
        assertEquals(p(500.0, 1050.0), pan.to)
        assertEquals(2.0, moves.filterIsInstance<CameraGesture.Zoom>().single().factor, 1e-12)
        assertEquals(PI / 2, moves.filterIsInstance<CameraGesture.Turn>().single().angle, 1e-12)
    }

    @Test
    fun `two fingers side by side moving up together tilt, and keep tilting until one lifts`() {
        val g = TouchGestures(slopPx = 20.0)
        g.fingers(mapOf(1 to p(400.0, 1000.0), 2 to p(600.0, 1010.0)), height)
        val first = g.fingers(mapOf(1 to p(402.0, 960.0), 2 to p(601.0, 970.0)), height)
        assertEquals(40.0 / height * PI, (first.single() as CameraGesture.Tilt).by, 1e-12)
        // Once a tilt, a spread is no zoom.
        val next = g.fingers(mapOf(1 to p(380.0, 940.0), 2 to p(640.0, 950.0)), height)
        assertTrue(next.all { it is CameraGesture.Tilt })
        // Down the view tilts the other way.
        assertTrue((g.fingers(mapOf(1 to p(380.0, 1000.0), 2 to p(640.0, 1010.0)), height).single() as CameraGesture.Tilt).by < 0)
    }

    @Test
    fun `two fingers one above the other moving up are a pan, not a tilt`() {
        val g = TouchGestures(slopPx = 20.0)
        g.fingers(mapOf(1 to p(500.0, 800.0), 2 to p(505.0, 1200.0)), height)
        val moves = g.fingers(mapOf(1 to p(500.0, 760.0), 2 to p(505.0, 1160.0)), height)
        assertTrue(moves.none { it is CameraGesture.Tilt })
        assertTrue(moves.any { it is CameraGesture.Pan })
    }

    @Test
    fun `nothing moves until two fingers have moved past the slop`() {
        val g = TouchGestures(slopPx = 20.0)
        g.fingers(mapOf(1 to p(400.0, 1000.0), 2 to p(600.0, 1000.0)), height)
        assertTrue(g.fingers(mapOf(1 to p(402.0, 1003.0), 2 to p(598.0, 999.0)), height).isEmpty())
    }

    @Test
    fun `a finger left of a pinch goes on panning from where it is, with no jump, and a third finger holds everything still`() {
        val g = TouchGestures(slopPx = 20.0)
        g.fingers(mapOf(1 to p(400.0, 1000.0), 2 to p(600.0, 1000.0)), height)
        g.fingers(mapOf(1 to p(350.0, 1000.0), 2 to p(650.0, 1000.0)), height)
        assertTrue(g.fingers(mapOf(1 to p(350.0, 1000.0)), height).isEmpty())                   // a finger lifted: nothing jumps
        assertEquals(listOf(CameraGesture.Pan(p(350.0, 1000.0), p(355.0, 1000.0))), g.fingers(mapOf(1 to p(355.0, 1000.0)), height))
        g.fingers(mapOf(1 to p(355.0, 1000.0), 2 to p(500.0, 500.0), 3 to p(700.0, 700.0)), height)
        assertTrue(g.fingers(mapOf(1 to p(455.0, 1000.0), 2 to p(500.0, 600.0), 3 to p(700.0, 900.0)), height).isEmpty())
        g.lifted()
        // A new touch starts afresh, slop and all.
        g.fingers(mapOf(7 to p(0.0, 0.0)), height)
        assertTrue(g.fingers(mapOf(7 to p(5.0, 0.0)), height).isEmpty())
    }

    @Test
    fun `a twist past half a turn between two readings is read the short way round`() {
        val g = TouchGestures(slopPx = 1.0)
        g.fingers(mapOf(1 to p(400.0, 1000.0), 2 to p(600.0, 1000.0)), height)
        g.fingers(mapOf(1 to p(400.0, 1010.0), 2 to p(600.0, 990.0)), height)
        val turn = g.fingers(mapOf(1 to p(600.0, 1010.0), 2 to p(400.0, 990.0)), height).filterIsInstance<CameraGesture.Turn>().single()
        assertTrue(abs(turn.angle) <= PI)
    }
}
