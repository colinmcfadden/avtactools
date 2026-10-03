package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import app.ezpztac.model.WeatherObservation
import app.ezpztac.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a device keeps of the last weather, and that a damaged file costs only what is damaged. */
class WeatherCodecTest {
    private val full = WeatherObservation(
        "KRYY", "Cobb County Airport", 0.7, 270, false, 12.0, 20.0, 18.0, 9.0, 30.0, "10", "VFR", "KRYY 031655Z 27012G20KT 10SM CLR 18/09 A3000",
    )
    private val bare = WeatherObservation("KCNI", "Cherokee County", 1.5, null, true, 3.0, null, 16.5, null, null, "10+", null, null)

    private fun snapshot(observation: WeatherObservation?, notams: Notams, at: LatLon = LatLon(34.0, -84.6), fetched: Long = 1_791_000_000_123) =
        WeatherSnapshot(at, fetched, observation, notams)

    private val listed = Notams.Listed(listOf(NotamGroup("Obstruction", listOf("!FDC 6/1234 CRANE", "A \"quoted\" \\ ÀÉÎ ⛰ text")), NotamGroup("Airspace", listOf("!ZTL 10/044 TFR"))))

    @Test
    fun `every shape of snapshot reads back as it was written`() {
        val all = mapOf(
            "a" to snapshot(full, listed),
            "b" to snapshot(bare, Notams.Clear, LatLon(-33.9, 151.2)),
            "c" to snapshot(null, Notams.Unavailable, LatLon(0.0, 0.0), fetched = 0),
        )
        assertEquals(all, WeatherCodec.decode(WeatherCodec.encode(all)))
    }

    @Test
    fun `nothing kept is nothing read`() {
        assertEquals(emptyMap<String, WeatherSnapshot>(), WeatherCodec.decode(WeatherCodec.encode(emptyMap())))
    }

    @Test
    fun `text that is not this format is nothing, never a crash`() {
        for (junk in listOf("", "not json", "[]", "\"x\"", "{}", """{"version":2,"entries":{}}""", """{"version":1}""", """{"version":1,"entries":[]}""", "{\"version\":1,\"entries\":{\"a\":3}}")) {
            assertTrue(junk, WeatherCodec.decode(junk).isEmpty())
        }
    }

    @Test
    fun `an entry that cannot be read is left out and the others are kept`() {
        val good = WeatherCodec.encode(mapOf("good" to snapshot(full, Notams.Clear)))
        val withBad = good.replace("\"entries\":{", "\"entries\":{\"noLat\":{\"lon\":1,\"fetchedAt\":1,\"observation\":null,\"notams\":{\"kind\":\"clear\"}},\"badNotams\":{\"lat\":1,\"lon\":2,\"fetchedAt\":1,\"observation\":null,\"notams\":{\"kind\":\"???\"}},\"badObs\":{\"lat\":1,\"lon\":2,\"fetchedAt\":1,\"observation\":{\"stationId\":5},\"notams\":{\"kind\":\"clear\"}},\"emptyListed\":{\"lat\":1,\"lon\":2,\"fetchedAt\":1,\"observation\":null,\"notams\":{\"kind\":\"listed\",\"groups\":[]}},")
        assertEquals(setOf("good"), WeatherCodec.decode(withBad).keys)
    }

    @Test
    fun `a missing time is not a time, so the entry is not kept as if it were fresh`() {
        val text = WeatherCodec.encode(mapOf("a" to snapshot(full, Notams.Clear))).replace("\"fetchedAt\":1791000000123,", "")
        assertTrue(WeatherCodec.decode(text).isEmpty())
    }

    @Test
    fun `a null number stays null, and is not turned into a zero`() {
        val read = WeatherCodec.decode(WeatherCodec.encode(mapOf("a" to snapshot(bare, Notams.Clear)))).getValue("a").observation!!
        assertNull(read.windFromDegrees)
        assertNull(read.windGustKt)
        assertNull(read.dewpointC)
        assertNull(read.altimeterInHg)
        assertNull(read.flightCategory)
        assertNull(read.rawReport)
    }
}
