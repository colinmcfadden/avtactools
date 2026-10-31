package app.ezpztac.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeatherTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `an age is in words, by the largest unit that fits`() {
        assertEquals("just now", WeatherAge.words(0))
        assertEquals("just now", WeatherAge.words(59_999))
        assertEquals("1 min ago", WeatherAge.words(minute))
        assertEquals("59 min ago", WeatherAge.words(59 * minute + 59_999))
        assertEquals("1 h ago", WeatherAge.words(hour))
        assertEquals("3 h ago", WeatherAge.words(3 * hour + 40 * minute))
        assertEquals("47 h ago", WeatherAge.words(47 * hour + 59 * minute))
        assertEquals("2 days ago", WeatherAge.words(48 * hour))
        assertEquals("10 days ago", WeatherAge.words(10 * 24 * hour + 5 * hour))
    }

    @Test
    fun `a clock that went backwards is just now, not a negative age`() {
        assertEquals("just now", WeatherAge.words(-5 * minute))
        assertEquals("just now", WeatherAge.words(Long.MIN_VALUE / 2))
    }

    @Test
    fun `a report is stale after ninety minutes, and not before`() {
        val snapshot = WeatherSnapshot(LatLon(34.0, -84.0), fetchedAtMillis = 1_000_000, observation = null, notams = Notams.Clear)
        assertFalse(snapshot.isStale(1_000_000 + 90 * minute))
        assertTrue(snapshot.isStale(1_000_000 + 90 * minute + 1))
        assertFalse(snapshot.isStale(1_000_000 - hour))                                         // from the future is not stale
        assertEquals("12 min ago", snapshot.age(1_000_000 + 12 * minute))
    }

    @Test
    fun `listed notams are counted across their groups`() {
        val listed = Notams.Listed(listOf(NotamGroup("Obstruction", listOf("a", "b")), NotamGroup("Airspace", listOf("c"))))
        assertEquals(3, listed.count)
        assertEquals(0, Notams.Listed(emptyList()).count)
    }
}
