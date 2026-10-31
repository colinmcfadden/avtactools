package app.ezpztac.formats

import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * The reader against what SQLite itself says is in each table (`contracts/fixtures/sqlite/tables.json`,
 * written from Python's `sqlite3`). The web's reader is held to the same file, so the two cannot drift apart.
 */
class SqliteFixtureTest {
    private val tables = Fixtures.load("sqlite/tables.json")

    @TestFactory
    fun `every table of every file reads as SQLite reads it`(): List<DynamicTest> =
        tables.flatMap { (file, contents) ->
            contents.jsonObject.map { (table, expected) ->
                DynamicTest.dynamicTest("$file / $table") {
                    val actual = requireNotNull(SqliteReader(Fixtures.bytes("sqlite/$file")).readTable(table)) { "no table $table" }
                    val columns = expected.jsonObject.getValue("columns").jsonArray.map { (it as JsonPrimitive).content }
                    val rows = expected.jsonObject.getValue("rows").jsonArray
                    assertEquals(columns, actual.columns)
                    assertEquals(rows.size, actual.rows.size, "row count")
                    rows.forEachIndexed { r, row ->
                        row.jsonArray.forEachIndexed { c, value ->
                            val problem = SqlValues.difference(value, actual.rows[r][c])
                            assertNull(problem, "$file / $table row ${r + 1}, ${columns[c]}: $problem")
                        }
                    }
                }
            }
        }

    @Test
    fun `the fixtures cover what the reader is for`() {
        fun rows(file: String, table: String) = tables.getValue(file).jsonObject.getValue(table).jsonObject.getValue("rows").jsonArray
        assertEquals(3000, rows("deep-tree.db", "Numbers").size)                       // a b-tree three levels deep
        assertTrue(rows("local-points-large.lps", "Points").size > 400)               // interior pages
        val spill = rows("local-points-large.lps", "Points").maxOf { ((it.jsonArray[2] as? JsonPrimitive)?.content ?: "").length }
        assertTrue(spill > 5000, "a value long enough to spill onto overflow pages")
        val kinds = rows("values.db", "Mixed").map { (it.jsonArray[1] as JsonPrimitive).content }.toSet()
        assertEquals(setOf("null", "int", "real", "text", "blob"), kinds)
    }

    @Test
    fun `a table that is not there is null, and so is one asked for in another case`() {
        val reader = SqliteReader(Fixtures.bytes("sqlite/local-points.lps"))
        assertNull(reader.readTable("Nope"))
        assertEquals(13, reader.readTable("points")!!.rows.size)
        assertEquals(13, reader.readTable("POINTS")!!.rows.size)
    }

    @Test
    fun `a column is found without regard to case`() {
        val table = SqliteReader(Fixtures.bytes("sqlite/local-points.lps")).readTable("Points")!!
        val first = table.rows[0]
        assertEquals("3MILE", table.value(first, "id"))
        assertEquals("3MILE", table.value(first, "ID"))
        assertNull(table.value(first, "NoSuchColumn"))
    }

    @Test
    fun `text in either UTF-16 byte order reads as the text it is`() {
        for (file in listOf("utf16le.db", "utf16be.db")) {
            val rows = SqliteReader(Fixtures.bytes("sqlite/$file")).readTable("Words")!!.rows.map { it[1] }
            assertEquals(listOf("hello", "ÀÉÎ ⛰", "emoji 😀 pair", ""), rows, file)
        }
    }

    @Test
    fun `a file that is not SQLite is refused`() {
        assertThrows<SqliteException> { SqliteReader("not a database".toByteArray()) }
        assertThrows<SqliteException> { SqliteReader(ByteArray(0)) }
        assertThrows<SqliteException> { SqliteReader(ByteArray(4096)) }
    }
}

/** Comparing a value read from a row with the JSON of what SQLite says it is. */
internal object SqlValues {
    /** Null if they agree. */
    fun difference(expected: JsonElement, actual: Any?): String? = when {
        expected is JsonNull -> if (actual == null) null else "expected null, got ${show(actual)}"
        expected is JsonObject -> {
            val hex = (expected["hex"] as? JsonPrimitive)?.content
            if (actual is ByteArray && hex != null && actual.joinToString("") { "%02x".format(it) } == hex) null
            else "expected a blob of $hex, got ${show(actual)}"
        }
        expected is JsonArray -> "an array is not a value"
        expected is JsonPrimitive && expected.isString ->
            if (actual is String && actual == expected.content) null else "expected \"${expected.content}\", got ${show(actual)}"
        expected is JsonPrimitive -> {
            val number = expected.content.toDouble()
            when (actual) {
                // SQLite keeps a whole-number REAL as an integer on disk, and Python reads it back as 921.0.
                is Long -> if (actual.toDouble() == number && (expected.content.toLongOrNull()?.let { it == actual } ?: true)) null
                           else "expected ${expected.content}, got $actual"
                is Double -> if (actual == number) null else "expected ${expected.content}, got $actual"
                else -> "expected ${expected.content}, got ${show(actual)}"
            }
        }
        else -> "unexpected fixture value"
    }

    private fun show(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> "a blob of ${value.size} bytes"
        is String -> "\"$value\""
        else -> "$value (${value::class.simpleName})"
    }
}
