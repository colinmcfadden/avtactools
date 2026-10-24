package app.ezpztac.scene3d

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The camera: Cesium's field of view, what it sees, where its matrices put things, and the web's opening view. */
class CameraTest {
    private val deg = PI / 180

    private fun camera(heading: Double = 0.0, pitch: Double = 0.0, width: Int = 1200, height: Int = 800, position: Vec3 = Vec3.ZERO) =
        ViewCamera(position, heading, pitch, width, height, near = 1.0, far = 10_000.0)

    @Test
    fun `the field of view is Cesium's sixty degrees across the wider side`() {
        val wide = camera(width = 1200, height = 800)
        assertEquals(2 * atan(tan(PI / 6) / 1.5), wide.fovy, 1e-12)
        val tall = camera(width = 800, height = 1200)
        assertEquals(PI / 3, tall.fovy, 1e-12)
        assertEquals(2 * tan(PI / 6), tall.sseDenominator, 1e-12)
    }

    @Test
    fun `it faces where its heading and pitch say, with north up when it looks straight down`() {
        val north = camera()
        assertEquals(1.0, north.direction.y, 1e-12)
        assertEquals(1.0, north.up.z, 1e-12)
        assertEquals(1.0, north.right.x, 1e-12)
        val east = camera(heading = 90 * deg)
        assertEquals(1.0, east.direction.x, 1e-12)
        val down = camera(pitch = -90 * deg)
        assertEquals(-1.0, down.direction.z, 1e-12)
        assertEquals(1.0, down.up.y, 1e-12)
        val (h, p) = ViewCamera.orientationOf(camera(heading = 330 * deg, pitch = -35 * deg).direction)
        assertEquals((330 - 360) * deg, h, 1e-12)
        assertEquals(-35 * deg, p, 1e-12)
    }

    @Test
    fun `it sees what is in front of it and nothing behind, beside or beyond`() {
        val c = camera()
        fun seen(p: Vec3) = c.frustum.visibility(Sphere(p, 0.0)) != Visibility.OUTSIDE
        assertTrue(seen(Vec3(0.0, 100.0, 0.0)))
        assertTrue(seen(Vec3(30.0, 100.0, 20.0)))
        assertTrue(!seen(Vec3(0.0, -100.0, 0.0)))                                                 // behind
        assertTrue(!seen(Vec3(0.0, 0.5, 0.0)))                                                    // nearer than near
        assertTrue(!seen(Vec3(0.0, 20_000.0, 0.0)))                                               // beyond far
        val halfWidth = tan(c.fovy / 2) * c.aspect * 100
        assertTrue(seen(Vec3(halfWidth - 1, 100.0, 0.0)))
        assertTrue(!seen(Vec3(halfWidth + 1, 100.0, 0.0)))
        assertTrue(!seen(Vec3(-halfWidth - 1, 100.0, 0.0)))
        val halfHeight = tan(c.fovy / 2) * 100
        assertTrue(!seen(Vec3(0.0, 100.0, halfHeight + 1)))
        assertTrue(!seen(Vec3(0.0, 100.0, -halfHeight - 1)))
        assertEquals(Visibility.INTERSECTING, c.frustum.visibility(Sphere(Vec3(halfWidth, 100.0, 0.0), 5.0)))
        assertEquals(Visibility.INSIDE, c.frustum.visibility(Sphere(Vec3(0.0, 100.0, 0.0), 5.0)))
    }

    @Test
    fun `its view puts it at the eye looking down minus z, and its projection keeps the near and far planes`() {
        val c = camera(heading = 40 * deg, pitch = -20 * deg, position = Vec3(10.0, -20.0, 400.0))
        val eye = c.view.transformPoint(c.position)
        assertEquals(0.0, eye.length, 1e-9)
        val ahead = c.view.transformPoint(c.position + c.direction * 50.0)
        assertEquals(-50.0, ahead.z, 1e-9)
        assertEquals(0.0, ahead.x, 1e-9)
        val near = c.projection.transformPoint(Vec3(0.0, 0.0, -1.0))
        val far = c.projection.transformPoint(Vec3(0.0, 0.0, -10_000.0))
        assertEquals(-1.0, near.z, 1e-9)
        assertEquals(1.0, far.z, 1e-9)
    }

    @Test
    fun `a ray through the middle of the view is the direction it faces, and one through a corner the frustum's edge`() {
        val c = camera(heading = 10 * deg, pitch = -30 * deg)
        val middle = c.rayThrough(600.0, 400.0)
        assertEquals(1.0, middle dot c.direction, 1e-12)
        val corner = c.rayThrough(1200.0, 0.0)
        assertEquals(Visibility.INTERSECTING, c.frustum.visibility(Sphere(c.position + corner * 100.0, 0.001)))
    }

    @Test
    fun `the view opens as the web's does, from 330 degrees and 35 down at 2,2 times the cloud's radius`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val center = Vec3(0.0, 0.0, 380.0)
        val radius = 120.0
        val (position, heading, pitch) = lookAt(frame, center, -30 * deg, -35 * deg, 2.2 * radius)
        val offset = position - center
        val range = 2.2 * radius
        // Cesium's offsetFromHeadingPitchRange: the camera stands back from the look direction; east of it and south, and above.
        assertEquals(-range * cos(-35 * deg) * sin(-30 * deg), offset.x, 1e-6)
        assertEquals(-range * cos(-35 * deg) * cos(-30 * deg), offset.y, 1e-6)
        assertEquals(-range * sin(-35 * deg), offset.z, 1e-6)
        assertEquals(-30 * deg, heading, 1e-9)
        assertEquals(-35 * deg, pitch, 1e-9)
    }

    @Test
    fun `away from the origin it looks at its centre in that centre's own frame`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val far = frame.fromGeodetic(34.65, -84.0, 400.0)
        val (position, heading, pitch) = lookAt(frame, far, 0.0, -90 * deg, 500.0)
        val (eastThere, _, upThere) = frame.axesAt(34.65, -84.0)
        assertEquals(1.0, (position - far).normalized() dot upThere, 1e-12)
        // The camera's heading and pitch are the scene's, whose up is not the centre's 13 km away: it looks down that centre's up to within the
        // angle between the two, and faces that centre's north (Cesium's right is its east, looking straight down).
        val between = acos((upThere dot Vec3.UNIT_Z).coerceIn(-1.0, 1.0))
        val looking = camera(heading = heading, pitch = pitch)
        assertTrue(acos((-(looking.direction dot upThere)).coerceIn(-1.0, 1.0)) <= 2.5 * between, "looks ${looking.direction} for ${-upThere}")
        assertEquals(1.0, looking.right dot Vec3(eastThere.x, eastThere.y, 0.0).normalized(), 1e-9)
    }

    @Test
    fun `looking straight down it faces north, as Cesium's camera does, wherever the centre is`() {
        // The review's case: 200 m east and 150 m north of the origin, whose up the centre's is a few arcseconds from. Read from the look alone,
        // straight down has no heading at all, and the arcseconds gave it one: west for the one, south for the other.
        val frame = LocalFrame(34.591552, -84.128225)
        for (center in listOf(Vec3(200.0, 0.0, 370.0), Vec3(0.0, 150.0, 370.0))) {
            val (_, heading, pitch) = lookAt(frame, center, 0.0, -90 * deg, 500.0)
            assertEquals(0.0, heading, 1e-4, "$center")
            assertEquals(-90 * deg, pitch, 1e-4, "$center")
            // A heading asked for there is not kept straight down (Cesium's right falls back to east), and is kept a hair off it.
            assertEquals(0.0, lookAt(frame, center, 45 * deg, -90 * deg, 500.0).second, 1e-4, "$center")
            assertEquals(45 * deg, lookAt(frame, center, 45 * deg, -89.99 * deg, 500.0).second, 1e-3, "$center")
            assertEquals(0.0, lookAt(frame, center, 0.0, -89.99 * deg, 500.0).second, 1e-3, "$center")
        }
    }
}
