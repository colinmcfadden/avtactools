package app.ezpztac.formats

import app.ezpztac.model.LocalPointSet
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/** `.LPS` files read to what the web's `parseLpsFile` reads out of them (`contracts/fixtures/localpoints/parse.json`). */
class LocalPointsFixtureTest {
    private val recorded = Fixtures.load("localpoints/parse.json")
    private val tolerance = (recorded["tolerance"] as JsonPrimitive).content.toDouble()
    private val json = Json { encodeDefaults = true }

    private fun read(file: String): LocalPointSet = LpsReader.read(Fixtures.bytes("sqlite/$file"), file)

    @TestFactory
    fun `each file reads as the web reads it`(): List<DynamicTest> =
        recorded.getValue("files").jsonObject.map { (file, expected) ->
            DynamicTest.dynamicTest(file) {
                val actual = json.parseToJsonElement(json.encodeToString(read(file)))
                val differences = JsonCompare.differences(expected, actual, tolerance)
                assertTrue(differences.isEmpty(), differences.joinToString("\n"))
            }
        }

    @Test
    fun `the refusals say what the web says`() {
        val errors = recorded.getValue("errors").jsonObject
        fun message(key: String) = (errors.getValue(key) as JsonPrimitive).content
        assertEquals(message("not SQLite"), assertThrows<FormatException> { LpsReader.read("not a database".toByteArray(), "junk.lps") }.message)
        assertEquals(message("no Points table"), assertThrows<FormatException> { read("deep-tree.db") }.message)
        assertEquals(message("no readable points"), assertThrows<FormatException> { read("local-points-unreadable.lps") }.message)
    }

    @Test
    fun `the set is named for the file, without its extension in any case`() {
        val bytes = Fixtures.bytes("sqlite/local-points.lps")
        assertEquals("NORTH GEORGIA POINTS", LpsReader.read(bytes, "NORTH GEORGIA POINTS.LPS").name)
        assertEquals("a.b", LpsReader.read(bytes, "a.b.lps").name)
        assertEquals("no extension", LpsReader.read(bytes, "no extension").name)
        assertEquals("lps.lps", LpsReader.read(bytes, "lps.lps.lps").name)                   // only the last one goes
    }

    @Test
    fun `what the fixtures are there to show`() {
        val points = read("local-points.lps").points
        assertEquals(
            listOf("3MILE", "PAD 7", "BLANKGRP", "NOGROUP", "BIGENDIAN", "TEXTELEV", "", "ÀÉÎ ⛰", "EDGE"),
            points.map { it.name },
        )
        assertEquals("", points.first { it.name == "BLANKGRP" }.group)               // blank-but-present stays blank
        assertEquals("Default", points.first { it.name == "NOGROUP" }.group)          // empty becomes Default
        assertEquals(null, points.first { it.name == "TEXTELEV" }.elevationFt)
        assertEquals(921.0, points.first { it.name == "3MILE" }.elevationFt)
        assertEquals(422, read("local-points-large.lps").points.size)
        val spill = read("local-points-large.lps").points.first { it.name == "SPILL" }
        assertTrue(spill.description.length > 5000 && spill.description.endsWith("end of a long description"))
    }

    @Test
    fun `the expected answer is a JSON object per file, so a missing file is noticed`() {
        assertEquals(setOf("local-points.lps", "local-points-large.lps"), recorded.getValue("files").jsonObject.keys)
        assertTrue(recorded.getValue("files") is JsonObject)
    }
}
