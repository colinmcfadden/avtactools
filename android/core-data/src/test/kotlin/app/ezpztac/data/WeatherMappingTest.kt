package app.ezpztac.data

import app.ezpztac.model.LatLon
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import app.ezpztac.network.WeatherReportDto
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the server's weather answer comes to: the real answers it gave, and the shapes the stations and the FAA can send. */
class WeatherMappingTest {
    private val json = Json { ignoreUnknownKeys = false }
    private val here = LatLon(34.0, -84.6)

    private fun recorded(name: String): WeatherReportDto {
        val body = Fixtures.load("network/responses.json").getValue("responses").jsonArray.map { it.jsonObject }
            .single { (it["name"] as JsonPrimitive).content == name }.getValue("body")
        return json.decodeFromJsonElement(WeatherReportDto.serializer(), body)
    }

    private fun snapshot(report: WeatherReportDto, now: Long = 1_000L) = WeatherMapping.snapshotOf(report, here, now)

    private fun report(vararg fields: Pair<String, Any?>): WeatherReportDto {
        val base = mutableMapOf<String, Any?>("station_id" to "KXXX", "name" to "Somewhere", "notams" to buildJsonObject { })
        base.putAll(fields)
        val obj = JsonObject(base.mapValues { (_, v) ->
            when (v) {
                null -> JsonNull
                is Number -> JsonPrimitive(v)
                is String -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> v as kotlinx.serialization.json.JsonElement
            }
        })
        return json.decodeFromJsonElement(WeatherReportDto.serializer(), obj)
    }

    // -- What the server really answered -------------------------------------------------------------------------------------------

    @Test
    fun `the nearest station's report is read whole`() {
        val s = snapshot(recorded("weather"), now = 5_000)
        val o = s.observation!!
        assertEquals("KRYY", o.stationId)
        assertEquals("Cobb County Airport", o.stationName)
        assertEquals(270, o.windFromDegrees)
        assertFalse(o.windVariable)
        assertEquals(12.0, o.windSpeedKt!!, 0.0)
        assertEquals(20.0, o.windGustKt!!, 0.0)
        assertEquals(18.0, o.tempC!!, 0.0)
        assertEquals(9.0, o.dewpointC!!, 0.0)
        assertEquals(30.0, o.altimeterInHg!!, 0.0)
        assertEquals("10", o.visibility)
        assertEquals("VFR", o.flightCategory)
        assertEquals(0.7, o.distanceMiles, 0.0)
        assertTrue(o.rawReport!!.startsWith("KRYY 031655Z"))
        assertEquals(here, s.at)
        assertEquals(5_000L, s.fetchedAtMillis)
    }

    @Test
    fun `NOTAMs are grouped as the FAA grouped them, trimmed, with the blank and the odd entries dropped`() {
        val notams = snapshot(recorded("weather")).notams as Notams.Listed
        assertEquals(
            listOf(
                NotamGroup("Obstruction", listOf("!FDC 6/1234 CRANE 340FT AGL 3NM N", "!FDC 6/2222 TOWER LGT OTS")),
                NotamGroup("Airspace", listOf("!ZTL 10/044 TEMPORARY FLIGHT RESTRICTION")),
            ).sortedBy { it.title },
            notams.groups.sortedBy { it.title },
        )
        assertEquals(3, notams.count)
    }

    @Test
    fun `a variable wind is variable, a visibility in text stays text, and no altimeter is none`() {
        val o = snapshot(recorded("weather: the second station is nearer")).observation!!
        assertEquals("KCNI", o.stationId)
        assertTrue(o.windVariable)
        assertNull(o.windFromDegrees)
        assertEquals(3.0, o.windSpeedKt!!, 0.0)
        assertNull(o.windGustKt)
        assertEquals("10+", o.visibility)
        assertNull(o.altimeterInHg)                                                          // the server says `--`: that is text, so nothing
        assertEquals(16.5, o.tempC!!, 0.0)
        assertEquals("MVFR", o.flightCategory)
    }

    @Test
    fun `no station is no observation, and an empty NOTAM search is clear`() {
        val s = snapshot(recorded("weather: no station and no NOTAMs"))
        assertNull(s.observation)
        assertEquals(Notams.Clear, s.notams)
    }

    @Test
    fun `a NOTAM search that could not be done is unavailable, which is not the same as clear`() {
        val down = snapshot(recorded("weather: both services down"))
        assertNull(down.observation)
        assertEquals(Notams.Unavailable, down.notams)
        val partial = snapshot(recorded("weather: the NOTAM search fails"))
        assertNotNull(partial.observation)
        assertEquals(Notams.Unavailable, partial.notams)
    }

    // -- Shapes the stations can send -------------------------------------------------------------------------------------------------

    @Test
    fun `a calm wind is a wind of nothing, and a missing wind is none`() {
        val calm = snapshot(report("wind_dir" to 0, "wind_spd_kts" to 0)).observation!!
        assertEquals(0, calm.windFromDegrees)
        assertEquals(0.0, calm.windSpeedKt!!, 0.0)
        val none = snapshot(report("wind_dir" to null, "wind_spd_kts" to null)).observation!!
        assertNull(none.windFromDegrees)
        assertNull(none.windSpeedKt)
        assertFalse(none.windVariable)
    }

    @Test
    fun `a direction is rounded to a whole degree, halves up`() {
        assertEquals(270, snapshot(report("wind_dir" to 270.4)).observation!!.windFromDegrees)
        assertEquals(270, snapshot(report("wind_dir" to 269.5)).observation!!.windFromDegrees)
        assertEquals(269, snapshot(report("wind_dir" to 269.49)).observation!!.windFromDegrees)
    }

    @Test
    fun `a number written as text is not a number, so it is none and not zero`() {
        val o = snapshot(report("temp_c" to "18", "dewp_c" to "x", "wind_spd_kts" to "12", "pressure" to "30.00", "wind_dir" to "270")).observation!!
        assertNull(o.tempC)
        assertNull(o.dewpointC)
        assertNull(o.windSpeedKt)
        assertNull(o.altimeterInHg)
        assertNull(o.windFromDegrees)
        assertFalse(o.windVariable)
    }

    @Test
    fun `a variable wind is recognised in any case, and only that word`() {
        assertTrue(snapshot(report("wind_dir" to "vrb")).observation!!.windVariable)
        assertFalse(snapshot(report("wind_dir" to "VARIABLE")).observation!!.windVariable)
    }

    @Test
    fun `visibility is kept as the station wrote it`() {
        assertEquals("10", snapshot(report("vis_sm" to 10)).observation!!.visibility)
        assertEquals("1.5", snapshot(report("vis_sm" to 1.5)).observation!!.visibility)
        assertEquals("1 1/2", snapshot(report("vis_sm" to "1 1/2")).observation!!.visibility)
        assertNull(snapshot(report("vis_sm" to "  ")).observation!!.visibility)
        assertNull(snapshot(report("vis_sm" to null)).observation!!.visibility)
    }

    @Test
    fun `an id or name that is blank is made sensible, and blank text fields are none`() {
        val o = snapshot(report("station_id" to "", "name" to "", "flight_category" to "", "raw_metar" to "  ")).observation!!
        assertEquals("UNKNOWN", o.stationId)
        assertEquals("", o.stationName)                                                      // an empty id has no name to fall back on
        assertNull(o.flightCategory)
        assertNull(o.rawReport)
    }

    @Test
    fun `a missing distance is zero miles`() {
        assertEquals(0.0, snapshot(report()).observation!!.distanceMiles, 0.0)
        assertEquals(12.3, snapshot(report("distance_miles" to 12.3)).observation!!.distanceMiles, 0.0)
    }

    // -- Shapes the NOTAMs can come in -------------------------------------------------------------------------------------------------

    private fun notams(obj: JsonObject) = snapshot(report("notams" to obj)).notams

    @Test
    fun `a Clear key is never a group, and with nothing else it is clear`() {
        assertEquals(Notams.Clear, notams(buildJsonObject { put("Clear", JsonArray(listOf(JsonPrimitive("No active NOTAMs in LZ area.")))) }))
        assertEquals(Notams.Clear, notams(JsonObject(emptyMap())))
    }

    @Test
    fun `a group with nothing readable in it is dropped, and what is left is listed`() {
        val result = notams(
            JsonObject(
                mapOf(
                    "Obstruction" to JsonArray(listOf(JsonPrimitive("  crane  "), JsonPrimitive(5), JsonPrimitive(""), JsonNull)),
                    "Empty" to JsonArray(emptyList()),
                    "Odd" to JsonPrimitive("not a list"),
                    "Clear" to JsonArray(listOf(JsonPrimitive("No active NOTAMs in LZ area."))),
                ),
            ),
        )
        assertEquals(Notams.Listed(listOf(NotamGroup("Obstruction", listOf("crane")))), result)
    }

    @Test
    fun `NOTAMs that are not an object are not known, whatever else they are`() {
        assertEquals(Notams.Unavailable, snapshot(report("notams" to "NOTAM fetch failed.")).notams)
        assertEquals(Notams.Unavailable, snapshot(report("notams" to null)).notams)
        assertEquals(Notams.Unavailable, snapshot(report("notams" to JsonArray(listOf(JsonPrimitive("x"))))).notams)
    }
}
