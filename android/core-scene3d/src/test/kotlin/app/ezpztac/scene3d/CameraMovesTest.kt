package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI

/** The camera's moves under the fingers, worked by hand over level ground: what is under a finger stays under it, and the limits hold. */
class CameraMovesTest {
    private val frame = LocalFrame(34.591552, -84.128225)
    private val ground = 370.0
    private val moves = CameraMoves(ground)
    private val deg = PI / 180

    private fun camera(pose: CameraPose) = pose.camera(1080, 2200, 2.75, 0.5, 1e5)

    /** 300 m from a point on the ground in front of it, looking at it 35° down from the south. */
    private val start = moves.orbit(Vec3(0.0, 0.0, ground), 300.0, 0.0, -35 * deg)

    /** The same, looking only 10° down: the top of a portrait view (60° up and down) is above the horizon. */
    private val shallow = moves.orbit(Vec3(0.0, 0.0, ground), 300.0, 0.0, -10 * deg)

    private fun assertNear(expected: Vec3, actual: Vec3, tolerance: Double = 1e-6) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test
    fun `the view opens on a sphere as the web's does, and the LZ button frames it so`() {
        val sphere = Sphere(Vec3(10.0, -20.0, 380.0), 150.0)
        val pose = CameraPose.framing(frame, sphere)
        val (position, heading, pitch) = lookAt(frame, sphere.center, -30 * deg, -35 * deg, 150.0 * 2.2)
        assertNear(position, pose.position)
        assertEquals(heading, pose.heading, 1e-12)
        assertEquals(pitch, pose.pitch, 1e-12)
    }

    @Test
    fun `the middle of the view is the ground point the camera looks at`() {
        val c = camera(start)
        assertNear(Vec3(0.0, 0.0, ground), moves.pivot(c), 1e-6)
        assertNear(Vec3(0.0, 0.0, ground), moves.ground(c, ScreenPoint(540.0, 1100.0))!!, 1e-6)
        // Above the horizon there is no ground under the finger.
        assertNull(moves.ground(camera(shallow), ScreenPoint(540.0, 0.0)))
    }

    @Test
    fun `one finger drags the ground under it, level, so what it went down on stays under it`() {
        val c = camera(start)
        val from = ScreenPoint(400.0, 1500.0)
        val to = ScreenPoint(700.0, 1300.0)
        val grabbed = moves.ground(c, from)!!
        val moved = camera(moves.pan(c, from, to))
        assertNear(grabbed, moves.ground(moved, to)!!, 1e-6)
        assertEquals(c.position.z, moved.position.z, 1e-9)
        assertEquals(c.heading, moved.heading)
        assertEquals(c.pitch, moved.pitch)
    }

    @Test
    fun `a finger above the horizon still pans, as far for a pixel as the ground in the middle is`() {
        val c = camera(shallow)
        assertNull(moves.ground(c, ScreenPoint(540.0, 10.0)))
        val moved = moves.pan(c, ScreenPoint(540.0, 10.0), ScreenPoint(540.0, 110.0))
        // Dragged down the view: the camera goes ahead, north, level.
        assertTrue(moved.position.y > c.position.y)
        assertEquals(c.position.z, moved.position.z, 1e-9)
    }

    @Test
    fun `a pinch goes toward what is between the fingers by as much as they spread, and no nearer than the limit`() {
        val c = camera(start)
        val at = ScreenPoint(300.0, 1400.0)
        val target = moves.ground(c, at)!!
        val before = (c.position - target).length
        val nearer = camera(moves.zoom(c, at, 2.0))
        assertEquals(before / 2, (nearer.position - target).length, 1e-6)
        assertNear(target, moves.ground(nearer, at)!!, 1e-6)                                     // what was between the fingers is still there
        val closest = moves.zoom(c, at, 1e6)
        assertEquals(moves.limits.minRange, (closest.position - target).length, 1e-6)
        val furthest = moves.zoom(c, at, 1e-9)
        assertEquals(moves.limits.maxRange, (furthest.position - target).length, 1e-6)
        assertEquals(c.position, moves.zoom(c, at, Double.NaN).position)
    }

    @Test
    fun `a twist turns the view with the fingers about the ground in the middle, which stays there`() {
        val c = camera(start)
        val turned = camera(moves.turn(c, 30 * deg))
        assertEquals(-30 * deg, turned.heading, 1e-12)                                           // clockwise fingers: north swings right
        assertNear(Vec3(0.0, 0.0, ground), moves.pivot(turned), 1e-6)
        assertEquals((c.position - moves.pivot(c)).length, (turned.position - moves.pivot(turned)).length, 1e-6)
    }

    @Test
    fun `two fingers up tilt toward the horizon about the middle, never past it nor below the ground`() {
        val c = camera(start)
        val tilted = camera(moves.tilt(c, 20 * deg))
        assertEquals(-15 * deg, tilted.pitch, 1e-12)
        assertNear(Vec3(0.0, 0.0, ground), moves.pivot(tilted), 1e-6)
        assertEquals(300.0, (tilted.position - moves.pivot(tilted)).length, 1e-6)
        assertEquals(moves.limits.maxPitch, moves.tilt(c, PI).pitch, 1e-12)
        assertEquals(-PI / 2, moves.tilt(c, -PI).pitch, 1e-12)
        // Five metres off the ground, level is as far as leaves it two metres up.
        val low = camera(moves.orbit(Vec3(0.0, 0.0, ground), 5.0, 0.0, -60 * deg))
        val lowest = moves.tilt(low, PI)
        assertTrue(lowest.position.z >= ground + moves.limits.minAboveGround - 1e-9, "${lowest.position.z}")
    }

    @Test
    fun `facing north keeps the ground in the middle, the distance and the pitch, and only turns`() {
        val c = camera(moves.turn(camera(start), 50 * deg))
        val north = camera(moves.facingNorth(c))
        assertEquals(0.0, north.heading, 1e-12)
        assertEquals(c.pitch, north.pitch, 1e-12)
        assertNear(moves.pivot(c), moves.pivot(north), 1e-6)
    }

    @Test
    fun `a flight eases from one pose to the other, turning the short way round`() {
        val from = CameraPose(Vec3(0.0, 0.0, 100.0), 350 * deg, -30 * deg)
        val to = CameraPose(Vec3(100.0, 0.0, 200.0), 10 * deg, -60 * deg)
        val flight = CameraFlight(from, to, 600)
        assertEquals(from, flight.at(0))
        val half = flight.at(300)
        assertNear(Vec3(50.0, 0.0, 150.0), half.position, 1e-9)
        assertEquals(360 * deg, half.heading, 1e-12)                                            // through north, not back round by south
        assertEquals(-45 * deg, half.pitch, 1e-12)
        val end = flight.at(600)
        assertNear(to.position, end.position, 1e-9)
        assertEquals(370 * deg, end.heading, 1e-12)
        assertTrue(flight.done(600) && !flight.done(599))
        assertNotNull(CameraFlight(from, to, 0).at(0).takeIf { it.position == to.position })
    }
}
