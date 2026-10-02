package app.ezpztac.geo

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Held to contracts/fixtures/mgrs, computed by PyGeodesy: the library behind the
 * server's /api/convert-to-mgrs and /api/convert-grid, which this replaces on
 * the device.
 */
class MgrsConverterTest {
    private val forward = Fixtures.load("mgrs/forward.json")
    private val inverse = Fixtures.load("mgrs/inverse.json")

    private fun JsonArray.double(i: Int) = this[i].jsonPrimitive.double
    private fun JsonArray.text(i: Int) = this[i].jsonPrimitive.content

    @Test
    fun `forward matches PyGeodesy at every point`() {
        val cases = forward["cases"]!!.jsonArray
        assertTrue(cases.size > 5000, "the fixture shrank to ${cases.size}")
        val misses = cases.map { it.jsonArray }.mapNotNull { c ->
            val actual = MgrsConverter.toMgrs(c.double(0), c.double(1))?.format()
            if (actual == c.text(2)) null else "${c.double(0)}, ${c.double(1)}: $actual (PyGeodesy ${c.text(2)})"
        }
        assertEquals(emptyList<String>(), misses.take(10), "${misses.size} of ${cases.size} differ")
    }

    @Test
    fun `forward matches the named edge cases`() {
        forward["named"]!!.jsonArray.map { it.jsonObject }.forEach { n ->
            val name = n["name"]!!.jsonPrimitive.content
            val actual = MgrsConverter.toMgrs(n["lat"]!!.jsonPrimitive.double, n["lon"]!!.jsonPrimitive.double)
            assertEquals(n["mgrs"]!!.jsonPrimitive.content, actual?.format(), name)
        }
    }

    @Test
    fun `polar caps have no answer`() {
        forward["noAnswer"]!!.jsonArray.map { it.jsonArray }.forEach { p ->
            assertNull(MgrsConverter.toMgrs(p.double(0), p.double(1)), "${p.double(0)}, ${p.double(1)}")
        }
    }

    @Test
    fun `a missing or non-finite coordinate has no answer`() {
        assertNull(MgrsConverter.toMgrs(Double.NaN, -84.0))
        assertNull(MgrsConverter.toMgrs(34.5, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `coarser grids truncate rather than round`() {
        // 66993 / 52949 m name the 10 m square 6699 / 5294, not 6700 / 5295.
        assertEquals("16S GD 6699 5294", MgrsConverter.toMgrs(34.783817, -84.08219, digits = 4)?.format())
    }

    @Test
    fun `a longitude carried past the antimeridian wraps`() {
        assertEquals(MgrsConverter.toMgrs(52.0, -179.9999), MgrsConverter.toMgrs(52.0, 180.0001))
        assertEquals("16S GD 66993 52949", MgrsConverter.toMgrs(34.783817, -84.08219 + 360)?.format())
    }

    @Test
    fun `inverse matches PyGeodesy at every grid`() {
        val tolerance = inverse["toleranceDeg"]!!.jsonPrimitive.double
        val cases = inverse["cases"]!!.jsonArray
        assertTrue(cases.size > 3000, "the fixture shrank to ${cases.size}")
        val misses = cases.map { it.jsonArray }.mapNotNull { c ->
            val grid = c.text(0)
            val actual = MgrsConverter.toLatLon(grid)
            when {
                actual == null -> "$grid: no answer"
                abs(actual.lat - c.double(1)) > tolerance || abs(actual.lon - c.double(2)) > tolerance ->
                    "$grid: ${actual.lat}, ${actual.lon} (PyGeodesy ${c.double(1)}, ${c.double(2)})"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), misses.take(10), "${misses.size} of ${cases.size} differ")
    }

    @Test
    fun `grids the server refuses have no answer`() {
        inverse["invalid"]!!.jsonArray.map { it.jsonPrimitive.content }.forEach { grid ->
            assertNull(MgrsConverter.toLatLon(grid), "'$grid' should be refused")
        }
    }

    @Test
    fun `a point survives the round trip to the metre`() {
        // forward truncates to the square's corner and inverse returns its centre,
        // so a 1 m grid comes back within the half-diagonal of that square.
        val grid = MgrsConverter.toMgrs(34.783817, -84.08219)!!
        val back = requireNotNull(MgrsConverter.toLatLon(grid)) { "no answer for $grid" }
        assertTrue(abs(back.lat - 34.783817) < 0.00001 && abs(back.lon - -84.08219) < 0.00001)
        assertEquals(grid, MgrsConverter.toMgrs(back.lat, back.lon))
    }
}
