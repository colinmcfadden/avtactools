package app.ezpztac.data

import android.database.sqlite.SQLiteDatabase
import app.ezpztac.formats.SqliteReader
import app.ezpztac.formats.ThsReader
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * The `.ths` the platform's SQLite writes, held to the file the backend writes for the same threats (`sqlite/threats.ths`, made by `build_ths_bytes` from
 * `threats/export.json`): every row of every table must be the one Python wrote, read back by our own reader, which is not the thing that wrote it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThsWriterTest {
    private val export = Fixtures.load("threats/export.json")
    private val json = Json { encodeDefaults = true }
    private val inputs: List<Threat> = json.decodeFromJsonElement(export.getValue("threats"))
    private val now = Instant.parse((export["nowUtc"] as JsonPrimitive).content)

    /** The bundled template: the backend's, which the app ships as an asset. */
    private val templateBytes = File("../../backend/threat_template.ths").also { check(it.isFile) { "run from android/core-data: ${it.absolutePath}" } }.readBytes()
    private val writer = ThsWriter { templateBytes }

    private fun tables(bytes: ByteArray, names: List<String>) = SqliteReader(bytes).let { r -> names.associateWith { r.readTable(it)!! } }

    @Test
    fun `every row of every table is the one the backend writes for the same threats`() {
        val ours = writer.build(inputs, now)
        val theirs = Fixtures.bytes("sqlite/threats.ths")
        for (table in listOf("THREATS", "THREATRADAR", "SYSTEM", "LINKS")) {
            val a = SqliteReader(ours).readTable(table)!!
            val b = SqliteReader(theirs).readTable(table)!!
            assertEquals("$table columns", b.columns, a.columns)
            assertEquals("$table rows", b.rows, a.rows)
        }
    }

    @Test
    fun `the schema comes out exactly as the template had it, with nothing added`() {
        val ours = writer.build(inputs, now)
        // The schema as SQLite itself lists it: our reader has no use for it, and "nothing was added" is a question for the engine that would have added it.
        fun master(bytes: ByteArray): List<List<String?>> {
            val file = File.createTempFile("schema", ".db")
            try {
                file.writeBytes(bytes)
                val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                try {
                    db.rawQuery("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY name", null).use { c ->
                        return generateSequence { if (c.moveToNext()) List(4) { i -> c.getString(i) } else null }.toList()
                    }
                } finally {
                    db.close()
                }
            } finally {
                file.delete()
            }
        }
        assertEquals(master(templateBytes), master(ours))
        assertFalse("android_metadata crept in", master(ours).any { it[1] == "android_metadata" })
        assertTrue("the template has tables to compare", master(ours).size >= 5)
    }

    @Test
    fun `what is written reads back as the threats that went in`() {
        val back = ThsReader.read(writer.build(inputs, now))
        assertEquals(ThsReader.read(Fixtures.bytes("sqlite/threats.ths")), back)
        assertEquals(inputs.size, back.size)
    }

    @Test
    fun `no threats make a template with its tables empty`() {
        val bytes = writer.build(emptyList(), now)
        for (table in listOf("THREATS", "THREATRADAR", "SYSTEM", "LINKS")) {
            assertEquals(table, 0, SqliteReader(bytes).readTable(table)!!.rows.size)
        }
    }

    @Test
    fun `a file built twice with the same time is the same threats twice, and what the template held is not carried over`() {
        val once = writer.build(inputs, now)
        val twice = writer.build(inputs, now)
        assertEquals(tables(once, listOf("THREATS")).getValue("THREATS").rows, tables(twice, listOf("THREATS")).getValue("THREATS").rows)
    }

    @Test
    fun `text is cut to the widths AMPS holds, a symbol outside the BMP whole`() {
        val long = Threat("N".repeat(60), "SHGPEWRR------EXTRA", 1.0, 2.0, "i".repeat(300), "s".repeat(40), radars = Radars.defaultPair())
        val read = SqliteReader(writer.build(listOf(long), now)).readTable("THREATS")!!
        val row = read.rows.single()
        assertEquals(50, (read.value(row, "OFFICIAL_NAME") as String).length)
        assertEquals(15, (read.value(row, "MILSTD_ID") as String).length)
        assertEquals(255, (read.value(row, "INFORMATION") as String).length)
        assertEquals(32, (read.value(row, "SOURCE") as String).length)
    }

    @Test
    fun `nothing of the threats is left in the temporary folder`() {
        val temp = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        fun threatFiles() = temp.list().orEmpty().filter { it.startsWith("threats") && (it.endsWith(".ths") || it.contains(".ths-")) }.toSet()
        val before = threatFiles()
        writer.build(inputs, now)
        assertEquals("left behind", before, threatFiles())
    }
}
