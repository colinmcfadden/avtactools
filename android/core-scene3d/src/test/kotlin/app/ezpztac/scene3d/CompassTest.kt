package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The compass as the web's turns and tilts it, every case of contracts/fixtures/viewer3d/compass.json. */
class CompassTest {
    private val fixture = Fixtures.load("viewer3d/compass.json")

    @Test
    fun `every pose is the web's`() {
        assertEquals(fixture.getValue("constants").jsonObject.getValue("MAX_TILT_DEG").jsonPrimitive.double, CompassPose.MAX_TILT_DEG)
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 100)
        for (c in cases) {
            val heading = c.getValue("headingRad").jsonPrimitive.double
            val pitch = c.getValue("pitchRad").jsonPrimitive.double
            val expected = c.getValue("expected").jsonObject
            val pose = CompassPose.of(heading, pitch)
            // The web's own arithmetic, so the numbers agree to the last digit or two (zero is zero, signed or not).
            assertEquals(expected.getValue("rotateDeg").jsonPrimitive.double, pose.rotateDeg, 1e-9, "rotate at $heading, $pitch")
            assertEquals(expected.getValue("tiltDeg").jsonPrimitive.double, pose.tiltDeg, 1e-9, "tilt at $heading, $pitch")
            assertEquals(expected.getValue("headingText").jsonPrimitive.content, pose.headingText, "text at $heading, $pitch")
        }
    }

    /** The web's CSS, step by step: rotateZ in the dial's plane (y down), rotateX leaning it back (z toward the eye), then the perspective. */
    private fun css(pose: CompassPose, x: Double, y: Double, sizePx: Double, cx: Double, cy: Double): Pair<Double, Double> {
        val t = Math.toRadians(pose.rotateDeg)
        val f = Math.toRadians(pose.tiltDeg)
        val x1 = x * Math.cos(t) - y * Math.sin(t)
        val y1 = x * Math.sin(t) + y * Math.cos(t)
        val y2 = y1 * Math.cos(f)
        val z2 = y1 * Math.sin(f)
        val s = CompassPose.DIAL_PERSPECTIVE / (CompassPose.DIAL_PERSPECTIVE - z2)
        val k = sizePx / CompassPose.DIAL_UNITS
        return cx + k * x1 * s to cy + k * y2 * s
    }

    private fun DoubleArray.map(x: Double, y: Double): Pair<Double, Double> {
        val w = this[6] * x + this[7] * y + this[8]
        return (this[0] * x + this[1] * y + this[2]) / w to (this[3] * x + this[4] * y + this[5]) / w
    }

    @Test
    fun `the dial is drawn as the web's CSS draws it, turned, leaned back and seen in perspective`() {
        // Straight down, facing north: the ring's top is straight above the middle.
        val down = CompassPose.of(0.0, -Math.PI / 2)
        val top = down.dialMatrix(198.0, 100.0, 120.0).map(0.0, -46.0)
        assertEquals(100.0, top.first, 1e-9)
        assertEquals(120.0 - 46 * 1.98, top.second, 1e-9)
        // Facing east, north is on the left.
        val east = CompassPose.of(Math.PI / 2, -Math.PI / 2).dialMatrix(100.0, 0.0, 0.0).map(0.0, -46.0)
        assertEquals(-46.0, east.first, 1e-9)
        assertEquals(0.0, east.second, 1e-9)
        for (pose in listOf(CompassPose.of(-0.52, -0.61), CompassPose.of(2.0, -0.1), CompassPose.of(4.0, 0.3), CompassPose.of(0.0, -1.2))) {
            val m = pose.dialMatrix(198.0, 300.0, 150.0)
            for (x in listOf(-46.0, -19.0, 0.0, 6.0, 29.0)) for (y in listOf(-46.0, -24.0, 0.0, 4.0, 33.0)) {
                val (ex, ey) = css(pose, x, y, 198.0, 300.0, 150.0)
                val (ax, ay) = m.map(x, y)
                assertEquals(ex, ax, 1e-9, "$pose at $x, $y")
                assertEquals(ey, ay, 1e-9, "$pose at $x, $y")
            }
        }
        // Leaned back, the top is further from the eye and so nearer the middle than the bottom is.
        val leaning = CompassPose.of(0.0, 0.0).dialMatrix(100.0, 0.0, 0.0)
        assertTrue(-leaning.map(0.0, -46.0).second < leaning.map(0.0, 46.0).second)
    }

    @Test
    fun `a screen reader hears the heading in words and what the button does`() {
        assertEquals("Facing 330 degrees true", CompassPose.of(Math.toRadians(-30.0), Math.toRadians(-35.0)).spoken)
        assertEquals("Facing 000 degrees true", CompassPose.of(Math.toRadians(359.6), 0.0).spoken)
    }
}
