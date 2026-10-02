package app.ezpztac.geo

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Held to contracts/fixtures/coords/parse.json, which records what the web's coordParse.js answers. */
class CoordinateParserTest {
    private val fixture = Fixtures.load("coords/parse.json")
    private val tolerance = fixture["toleranceDeg"]!!.jsonPrimitive.double

    private fun cases(key: String) = fixture[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text() = this["text"]!!.jsonPrimitive.content

    @Test
    fun `parses and refuses exactly what the web does`() {
        val cases = cases("parse")
        assertTrue(cases.size >= 50, "the fixture shrank to ${cases.size}")
        val misses = cases.mapNotNull { c ->
            val text = c.text()
            val actual = CoordinateParser.parse(text)
            val expected = c["expected"]!!
            when {
                expected is JsonNull -> if (actual == null) null else "'$text' parsed as ${actual.lat}, ${actual.lon} but the web refuses it"
                actual == null -> "'$text' was refused but the web reads it"
                else -> {
                    val e = expected.jsonObject
                    val problems = buildList {
                        if (abs(actual.lat - e["lat"]!!.jsonPrimitive.double) > tolerance) add("lat ${actual.lat} vs ${e["lat"]}")
                        if (abs(actual.lon - e["lon"]!!.jsonPrimitive.double) > tolerance) add("lon ${actual.lon} vs ${e["lon"]}")
                        if (actual.format != e["format"]!!.jsonPrimitive.content) add("format ${actual.format} vs ${e["format"]}")
                        if (actual.label != e["label"]!!.jsonPrimitive.content) add("label ${actual.label} vs ${e["label"]}")
                    }
                    if (problems.isEmpty()) null else "'$text': ${problems.joinToString("; ")}"
                }
            }
        }
        assertEquals(emptyList<String>(), misses, "${misses.size} of ${cases.size} differ")
    }

    @Test
    fun `tells a grid from a coordinate the way the web does`() {
        cases("looksLikeMgrs").forEach { c ->
            assertEquals(c["expected"]!!.jsonPrimitive.boolean, CoordinateParser.looksLikeMgrs(c.text()), "looksLikeMgrs('${c.text()}')")
        }
    }

    @Test
    fun `recognises a coordinate being typed the way the web does`() {
        cases("looksLikeCoordinateText").forEach { c ->
            assertEquals(c["expected"]!!.jsonPrimitive.boolean, CoordinateParser.looksLikeCoordinateText(c.text()), "looksLikeCoordinateText('${c.text()}')")
        }
    }

    @Test
    fun `formats a pair as toFixed does`() {
        cases("formatDecimal").forEach { c ->
            val lat = c["lat"]!!.jsonPrimitive.double
            val lon = c["lon"]!!.jsonPrimitive.double
            val places = c["places"]!!.jsonPrimitive.int
            assertEquals(c["expected"]!!.jsonPrimitive.content, CoordinateParser.formatDecimal(lat, lon, places), "$lat, $lon")
        }
    }

    @Test
    fun `writes degrees minutes and seconds as the web keeps them on an analysed diagram`() {
        val all = cases("latLongString")
        assertTrue(all.size >= 10)
        all.forEach { c ->
            val lat = c["lat"]!!.jsonPrimitive.double
            val lon = c["lon"]!!.jsonPrimitive.double
            assertEquals(c["expected"]!!.jsonPrimitive.content, CoordinateParser.formatLatLongDms(lat, lon), "$lat, $lon")
        }
    }

    @Test
    fun `rounds from the exact binary value like toFixed, not the shortest decimal like Java`() {
        // 1.005 is stored as 1.00499999999999989..., so JavaScript gives "1.00";
        // String.format would give "1.01".
        assertEquals("1.00", CoordinateParser.toFixed(1.005, 2))
        assertEquals("1.01", CoordinateParser.toFixed(1.0051, 2))
        assertEquals("0.13", CoordinateParser.toFixed(0.125, 2))     // an exact tie goes up
        assertEquals("-0.00000", CoordinateParser.toFixed(-0.000001, 5))
        assertEquals("0.00000", CoordinateParser.toFixed(-0.0, 5))
    }

    @Test
    fun `a grid is never read as a coordinate`() {
        listOf("16S GC 28864 55349", "18T WL 123 456", "4QFJ12345678").forEach {
            assertNull(CoordinateParser.parse(it), it)
        }
    }

    @Test
    fun `null and blank text are not coordinates`() {
        assertNull(CoordinateParser.parse(null))
        assertNull(CoordinateParser.parse("    "))
        assertEquals(false, CoordinateParser.looksLikeCoordinateText(null))
    }
}
