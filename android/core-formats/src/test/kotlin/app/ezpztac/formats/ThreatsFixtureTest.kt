package app.ezpztac.formats

import app.ezpztac.model.Threat
import app.ezpztac.testing.Fixtures
import app.ezpztac.testing.JsonCompare
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * `.ths` threat files: read as the web reads them (`threats/parse.json`), and written as the backend writes
 * them (`threats/export.json` for the inputs, `sqlite/tables.json` for the rows the backend's exporter produced).
 */
class ThreatsFixtureTest {
    private val parsed = Fixtures.load("threats/parse.json")
    private val export = Fixtures.load("threats/export.json")
    private val tables = Fixtures.load("sqlite/tables.json").getValue("threats.ths").jsonObject
    private val tolerance = (parsed["tolerance"] as JsonPrimitive).content.toDouble()
    private val json = Json { encodeDefaults = true }

    private fun inputs(): List<Threat> = json.decodeFromJsonElement<List<Threat>>(export.getValue("threats"))

    // -- Reading -------------------------------------------------------------------

    @TestFactory
    fun `each file reads as the web reads it`(): List<DynamicTest> =
        parsed.getValue("files").jsonObject.map { (file, expected) ->
            DynamicTest.dynamicTest(file) {
                val actual = json.parseToJsonElement(json.encodeToString(ThsReader.read(Fixtures.bytes("sqlite/$file"))))
                val differences = JsonCompare.differences(expected, actual, tolerance)
                assertTrue(differences.isEmpty(), differences.joinToString("\n"))
            }
        }

    @Test
    fun `the refusals say what the web says`() {
        val errors = parsed.getValue("errors").jsonObject
        fun message(key: String) = (errors.getValue(key) as JsonPrimitive).content
        assertEquals(message("not SQLite"), assertThrows<FormatException> { ThsReader.read("not a database".toByteArray()) }.message)
        assertEquals(message("no THREATS table"), assertThrows<FormatException> { ThsReader.read(Fixtures.bytes("sqlite/deep-tree.db")) }.message)
        assertEquals(message("no readable threats"), assertThrows<FormatException> { ThsReader.read(Fixtures.bytes("sqlite/threats-empty.ths")) }.message)
    }

    @Test
    fun `what the exporter wrote is what the reader gives back`() {
        val read = ThsReader.read(Fixtures.bytes("sqlite/threats.ths"))
        val sent = inputs()
        assertEquals(sent.size, read.size)
        sent.zip(read).forEachIndexed { i, (was, now) ->
            assertEquals(was.lat, now.lat, "threat ${i + 1} lat")
            assertEquals(was.lon, now.lon, "threat ${i + 1} lon")
            // A threat written with no radar comes back with the two defaults.
            val types = if (was.radars.isEmpty()) listOf(0, 1) else was.radars.map { it.type }
            assertEquals(types, now.radars.map { it.type }, "threat ${i + 1} radar types")
        }
        assertEquals("Threat 3", read[2].name)                               // the exporter named it
    }

    // -- Writing -------------------------------------------------------------------

    @Test
    fun `the date is written as AMPS writes it`() {
        val now = (export["nowUtc"] as JsonPrimitive).content
        assertEquals((export["dtg"] as JsonPrimitive).content, ThsExport.dtg(Instant.parse(now)))
        assertEquals("01000000012000", ThsExport.dtg(Instant.parse("2000-01-01T00:00:00Z")))
        assertEquals("31235959122099", ThsExport.dtg(Instant.parse("2099-12-31T23:59:59Z")))
    }

    @TestFactory
    fun `every row the backend writes, the app writes`(): List<DynamicTest> {
        val rows = ThsExport.rows(inputs(), (export["dtg"] as JsonPrimitive).content)
        val actual = mapOf("THREATS" to rows.threats, "THREATRADAR" to rows.radars, "SYSTEM" to rows.systems)
        return actual.map { (table, ours) ->
            DynamicTest.dynamicTest(table) {
                val expected = tables.getValue(table).jsonObject
                val columns = expected.getValue("columns").jsonArray.map { (it as JsonPrimitive).content }
                val theirs = expected.getValue("rows").jsonArray
                assertEquals(theirs.size, ours.size, "$table row count")
                ours.forEachIndexed { r, row ->
                    val unknown = row.keys - columns.toSet()
                    assertTrue(unknown.isEmpty(), "$table writes columns the template does not have: $unknown")
                    columns.forEachIndexed { c, column ->
                        val problem = SqlValues.difference(theirs[r].jsonArray[c], row[column])
                        assertNull(problem, "$table row ${r + 1}, $column: $problem")
                    }
                }
            }
        }
    }

    @Test
    fun `the exporter's rows cover the quirks the fixture is there to pin`() {
        val rows = ThsExport.rows(inputs(), "02123456102026")
        // Characters, not UTF-16 units: the emoji at the 50th character survives the cut.
        val names = rows.threats.map { it["OFFICIAL_NAME"] as String }
        assertEquals("A".repeat(49) + "😀", names[1])
        assertEquals("Threat 3", names[2])
        assertEquals("   ", names[6])                                       // spaces are text: only an empty name takes the default
        assertEquals(" ", rows.threats[6]["MILSTD_ID"])
        assertEquals(15, (rows.threats[1]["MILSTD_ID"] as String).length)
        assertEquals(255, (rows.threats[1]["INFORMATION"] as String).length)
        assertEquals(32, (rows.threats[1]["SOURCE"] as String).length)
        // Two bands: the third colour is 1, not 5; a fractional altitude is cut toward zero.
        val twoBands = rows.radars.single { it["ID"] == 4L }
        assertEquals(listOf(7L, 8L, 1L), listOf(twoBands["COLOR1"], twoBands["COLOR2"], twoBands["COLOR3"]))
        assertEquals(listOf(250L, 0L, 0L), listOf(twoBands["ELEVATION1"], twoBands["ELEVATION2"], twoBands["ELEVATION3"]))
        assertEquals(listOf(0L, 1L, 1L), listOf(twoBands["MASK1_VIEWABLE"], twoBands["MASK2_VIEWABLE"], twoBands["MASK3_VIEWABLE"]))
        // A threat with no radar still has a system row, using neither.
        val marker = rows.systems.single { it["SYSTEM_CODE"] == 6L }
        assertEquals(listOf(0L, 0L), listOf(marker["USE_ENGAGEMENT"], marker["USE_DETECTION"]))
        assertEquals(rows.threats.size, rows.systems.size)
    }

    @Test
    fun `text that fits in characters is not touched, though it is longer in UTF-16 units`() {
        val name = "😀".repeat(40)                                          // 40 characters, 80 UTF-16 units
        val threat = Threat(name, "SHGPEWMAI------", 34.0, -84.0, "😀".repeat(200), "S", radars = emptyList())
        val row = ThsExport.rows(listOf(threat), "02123456102026").threats.single()
        assertEquals(name, row["OFFICIAL_NAME"])
        assertEquals("😀".repeat(200), row["INFORMATION"])
        val long = ThsExport.rows(listOf(threat.copy(name = "😀".repeat(60))), "02123456102026").threats.single()
        assertEquals("😀".repeat(50), long["OFFICIAL_NAME"])
    }

    @Test
    fun `no threats, no rows`() {
        val rows = ThsExport.rows(emptyList(), "02123456102026")
        assertEquals(emptyList<Any>(), rows.threats + rows.radars + rows.systems)
    }
}
