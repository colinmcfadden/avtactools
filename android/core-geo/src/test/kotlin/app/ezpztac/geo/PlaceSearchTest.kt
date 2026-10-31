package app.ezpztac.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PlaceSearchTest {
    @Test
    fun `a grid, in any spacing and case, is found where the grid is`() {
        for (typed in listOf("16S GD 66993 52949", "16sgd6699352949", "  16S  GD  66993  52949 ")) {
            val place = PlaceSearch.resolve(typed) as PlaceResult.Found
            assertTrue(abs(place.at.lat - 34.783817) < 0.0001 && abs(place.at.lon - -84.08219) < 0.0001, typed)
            assertEquals("MGRS grid", place.description)
        }
    }

    @Test
    fun `a less exact grid is the middle of its square`() {
        val place = PlaceSearch.resolve("16S GD 66 52") as PlaceResult.Found
        assertNotNull(place.at)
        assertTrue(abs(place.at.lat - 34.78) < 0.05)
    }

    @Test
    fun `coordinates in the formats crews paste are understood, and the person is told which`() {
        val decimal = PlaceSearch.resolve("34.545678, -84.123456") as PlaceResult.Found
        assertEquals(34.545678, decimal.at.lat, 1e-9)
        assertEquals(-84.123456, decimal.at.lon, 1e-9)
        assertEquals("Decimal degrees", decimal.description)
        val ddm = PlaceSearch.resolve("34°32.740'N 084°07.407'W") as PlaceResult.Found
        assertEquals(34.5456667, ddm.at.lat, 1e-6)
        assertEquals("Degrees/decimal minutes", ddm.description)
    }

    @Test
    fun `what cannot be read says so`() {
        assertTrue(PlaceSearch.resolve("") is PlaceResult.NotUnderstood)
        assertTrue(PlaceSearch.resolve("   ") is PlaceResult.NotUnderstood)
        assertTrue(PlaceSearch.resolve("somewhere nice") is PlaceResult.NotUnderstood)
        assertTrue(PlaceSearch.resolve("99.0, 200.0") is PlaceResult.NotUnderstood)
        assertTrue(PlaceSearch.resolve("16S ZZ 123") is PlaceResult.NotUnderstood)
        assertEquals("Could not read that as a grid or a coordinate.", (PlaceSearch.resolve("somewhere nice") as PlaceResult.NotUnderstood).message)
    }
}
