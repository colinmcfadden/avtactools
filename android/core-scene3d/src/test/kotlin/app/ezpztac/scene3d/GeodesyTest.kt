package app.ezpztac.scene3d

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The scene's frame: geocentric coordinates held to PROJ (contracts/fixtures/viewer3d/geodesy.json), back again, and east-north-up on the LZ. */
class GeodesyTest {
    private val cases = Fixtures.load("viewer3d/geodesy.json").getValue("ecef").jsonArray.map { it.jsonObject }

    @Test
    fun `geocentric coordinates are PROJ's to a tenth of a millimetre`() {
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val lat = c.getValue("lat").jsonPrimitive.double
            val lon = c.getValue("lon").jsonPrimitive.double
            val h = c.getValue("height").jsonPrimitive.double
            val p = geodeticToEcef(lat, lon, h)
            assertEquals(c.getValue("x").jsonPrimitive.double, p.x, 1e-4, "x at $lat, $lon, $h")
            assertEquals(c.getValue("y").jsonPrimitive.double, p.y, 1e-4, "y at $lat, $lon, $h")
            assertEquals(c.getValue("z").jsonPrimitive.double, p.z, 1e-4, "z at $lat, $lon, $h")
        }
    }

    @Test
    fun `back from geocentric is the place again, the poles and the antimeridian included`() {
        for (c in cases) {
            val lat = c.getValue("lat").jsonPrimitive.double
            val lon = c.getValue("lon").jsonPrimitive.double
            val h = c.getValue("height").jsonPrimitive.double
            val back = ecefToGeodetic(Vec3(c.getValue("x").jsonPrimitive.double, c.getValue("y").jsonPrimitive.double, c.getValue("z").jsonPrimitive.double))
            assertEquals(lat, back.lat, 1e-9, "lat at $lat, $lon, $h")
            if (abs(lat) < 89.99) {
                val dLon = ((back.lon - lon + 540.0) % 360.0) - 180.0
                assertEquals(0.0, dLon, 1e-9, "lon at $lat, $lon, $h")
            }
            assertEquals(h, back.height, 1e-4, "height at $lat, $lon, $h")
        }
    }

    @Test
    fun `the frame on the LZ is east, north and up, and keeps the earth's curve`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val (e, n, u) = Triple(frame.east, frame.north, frame.up)
        for ((a, b) in listOf(e to n, n to u, u to e)) assertEquals(0.0, a dot b, 1e-15)
        for (axis in listOf(e, n, u)) assertEquals(1.0, axis.length, 1e-15)
        val cross = e cross n
        assertEquals(u.x, cross.x, 1e-15)
        assertEquals(u.y, cross.y, 1e-15)
        assertEquals(u.z, cross.z, 1e-15)
        // The origin is nought; a point a kilometre north on the ellipsoid is that far north and below the plane by the curve.
        val origin = frame.fromGeodetic(34.591552, -84.128225, 0.0)
        assertEquals(0.0, origin.length, 1e-6)
        val north = frame.fromGeodetic(34.591552 + 1000.0 / 110_934.0, -84.128225, 0.0)
        assertEquals(0.0, north.x, 1e-3)
        assertEquals(1000.0, north.y, 1.0)
        assertTrue(north.z < -0.07 && north.z > -0.09, "dropped ${north.z} m")
        val up = frame.fromGeodetic(34.591552, -84.128225, 500.0)
        assertEquals(Vec3(0.0, 0.0, 500.0).z, up.z, 1e-6)
        // And back.
        val back = frame.toGeodetic(Vec3(250.0, -400.0, 370.0))
        val again = frame.fromGeodetic(back.lat, back.lon, back.height)
        assertEquals(250.0, again.x, 1e-6)
        assertEquals(-400.0, again.y, 1e-6)
        assertEquals(370.0, again.z, 1e-6)
    }

    @Test
    fun `the matrix from geocentric is the frame's own move`() {
        val frame = LocalFrame(-33.8688, 151.2093, 58.0)
        val p = geodeticToEcef(-33.87, 151.21, 120.0)
        val byMatrix = frame.fromEcef.transformPoint(p)
        val byHand = frame.toLocal(p)
        assertEquals(byHand.x, byMatrix.x, 1e-6)
        assertEquals(byHand.y, byMatrix.y, 1e-6)
        assertEquals(byHand.z, byMatrix.z, 1e-6)
        val back = frame.toEcef.transformPoint(byHand)
        assertEquals(p.x, back.x, 1e-6)
        assertEquals(p.y, back.y, 1e-6)
        assertEquals(p.z, back.z, 1e-6)
        val identity = frame.toEcef * frame.fromEcef
        for (r in 0..3) for (c in 0..3) assertEquals(if (r == c) 1.0 else 0.0, identity[r, c], if (c == 3) 1e-6 else 1e-12)
    }

    @Test
    fun `north at another place is that place's own`() {
        val frame = LocalFrame(34.591552, -84.128225)
        val (_, northThere, upThere) = frame.axesAt(34.6, -84.1)
        val there = frame.fromGeodetic(34.6, -84.1, 0.0)
        val further = frame.fromGeodetic(34.6001, -84.1, 0.0)
        val along = (further - there).normalized()
        assertEquals(1.0, along dot northThere, 1e-6)
        assertEquals(0.0, along dot upThere, 1e-4)
    }

    @Test
    fun `a matrix and its inverse undo each other, and a flat one has none`() {
        val m = Mat4.fromAxes(Vec3(10.0, 0.0, 0.0), Vec3(0.0, 10.0, 0.0), Vec3(0.0, 0.0, 10.0), Vec3(-64.2, -48.7, -56.5))
        val inv = m.inverse()!!
        val p = Vec3(3.0, 4.0, 5.0)
        val back = inv.transformPoint(m.transformPoint(p))
        assertEquals(p.x, back.x, 1e-12)
        assertEquals(p.y, back.y, 1e-12)
        assertEquals(p.z, back.z, 1e-12)
        assertEquals(null, Mat4.fromAxes(Vec3.UNIT_X, Vec3.UNIT_X, Vec3.UNIT_Z).inverse())
        assertEquals(10.0, m.maximumScale, 1e-12)
    }
}
