package app.ezpztac.geo

import app.ezpztac.model.LatLon
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Held to contracts/fixtures/coords/reading.json, which records what the web's target field (utils/targetReading.js) offers. */
class PlaceReadingFixtureTest {
    private val fixture = Fixtures.load("coords/reading.json")
    private val tolerance = fixture["toleranceDeg"]!!.jsonPrimitive.double

    private fun cases(key: String) = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text() = this["text"]!!.jsonPrimitive.content
    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `offers what the web offers for every text, choices and sentence`() {
        val cases = cases("read")
        assertTrue(cases.size >= 60, "the fixture shrank to ${cases.size}")
        val misses = cases.mapNotNull { c ->
            val expected = c["expected"]!!.jsonObject
            val actual = PlaceSearch.read(c.text())
            val message = expected["message"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val choices = expected["choices"]!!.jsonArray.map { it.jsonObject }
            val problems = buildList {
                if (actual.message != message) add("message '${actual.message}' vs '$message'")
                if (actual.choices.size != choices.size) {
                    add("${actual.choices.size} choices vs ${choices.size}")
                } else {
                    actual.choices.zip(choices).forEachIndexed { i, (a, e) ->
                        if (abs(a.lat - e["lat"]!!.jsonPrimitive.double) > tolerance) add("[$i] lat ${a.lat} vs ${e["lat"]}")
                        if (abs(a.lon - e["lon"]!!.jsonPrimitive.double) > tolerance) add("[$i] lon ${a.lon} vs ${e["lon"]}")
                        if (a.mgrs != e.string("mgrs")) add("[$i] mgrs ${a.mgrs} vs ${e.string("mgrs")}")
                        if (a.latLong != e.string("latLong")) add("[$i] latLong ${a.latLong} vs ${e.string("latLong")}")
                        if (a.how != e.string("how")) add("[$i] how ${a.how} vs ${e.string("how")}")
                    }
                }
            }
            if (problems.isEmpty()) null else "'${c.text()}': ${problems.joinToString("; ")}"
        }
        assertEquals(emptyList<String>(), misses, "${misses.size} of ${cases.size} differ")
    }

    @Test
    fun `orders the readings nearest the map first, as the web does`() {
        cases("nearestFirst").forEach { c ->
            val near = c["near"]!!.let { if (it is JsonNull) null else LatLon(it.jsonObject["lat"]!!.jsonPrimitive.double, it.jsonObject["lon"]!!.jsonPrimitive.double) }
            val expected = c["expected"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, PlaceSearch.nearestFirst(PlaceSearch.read(c.text()).choices, near).map { it.latLong }, "${c.text()} near $near")
        }
    }

    @Test
    fun `writes a lat and long in words as the web does`() {
        cases("formatLatLong").forEach { c ->
            val lat = c["lat"]!!.jsonPrimitive.double
            val lon = c["lon"]!!.jsonPrimitive.double
            assertEquals(c.string("expected"), PlaceSearch.formatLatLong(lat, lon), "$lat, $lon")
        }
    }

    @Test
    fun `reads back the lat and long it writes`() {
        cases("read").flatMap { c -> PlaceSearch.read(c.text()).choices }.forEach { choice ->
            val again = PlaceSearch.read(choice.latLong).choices.single()
            assertTrue(abs(again.lat - choice.lat) < 6e-6 && abs(again.lon - choice.lon) < 6e-6, choice.latLong)
        }
    }

    @Test
    fun `a byte order mark inside a grid is a space, as JavaScript's whitespace has it`() {
        assertEquals("16S GC 28864 55349", PlaceSearch.read("16S GC\uFEFF28864 55349").choices.single().mgrs)
    }
}
