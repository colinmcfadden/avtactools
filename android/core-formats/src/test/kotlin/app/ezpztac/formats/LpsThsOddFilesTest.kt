package app.ezpztac.formats

import app.ezpztac.model.Radars
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Files no real exporter would write but a person's file can be: a threat with no position, a column in another case,
 * text where a number should be. The web has no good answer for several of these (it reads a NULL position as 0°, 0° and a
 * bad number as NaN); where this reader differs it says so here.
 */
class LpsThsOddFilesTest {
    private fun point(lon: Double, lat: Double, order: ByteOrder = ByteOrder.LITTLE_ENDIAN, geometryClass: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(60).order(order)
        b.put(0, 0); b.put(1, if (order == ByteOrder.LITTLE_ENDIAN) 1 else 0)
        b.putInt(39, geometryClass); b.putDouble(43, lon); b.putDouble(51, lat); b.put(38, 0x7C); b.put(59, 0xFE.toByte())
        return b.array()
    }

    private val lpsSql = "CREATE TABLE Points (Pedigree INTEGER PRIMARY KEY, ID TEXT, Description TEXT, GroupName TEXT, IconName TEXT, Elevation REAL, Coordinate BLOB)"
    private fun lps(vararg rows: List<Any?>, sql: String = lpsSql, name: String = "Points") =
        MiniSqlite.build(MiniSqlite.Table(name, sql, rows = rows.toList()))

    // -- .LPS ---------------------------------------------------------------------------

    @Test
    fun `text is trimmed as JavaScript trims it, the byte order mark and the no-break space included`() {
        val read = LpsReader.read(lps(listOf(null, "﻿  A1  ", "\tdesc\n", " G ", "　i　", 1L, point(-84.0, 34.0))), "x.lps")
        val p = read.points.single()
        assertEquals(listOf("A1", "desc", "G", "i"), listOf(p.name, p.description, p.group, p.icon))
    }

    @Test
    fun `a table and its columns are found without regard to case`() {
        val sql = "CREATE TABLE points (pedigree INTEGER PRIMARY KEY, id TEXT, coordinate BLOB, elevation REAL)"
        val read = LpsReader.read(lps(listOf(null, "low", point(-84.0, 34.0), 12L), sql = sql, name = "points"), "x.lps")
        assertEquals("low", read.points.single().name)
        assertEquals(12.0, read.points.single().elevationFt)
    }

    @Test
    fun `an integer, a real or no elevation, and anything else is no elevation`() {
        val read = LpsReader.read(lps(
            listOf(null, "int", "", "", "", 921L, point(-84.0, 34.0)),
            listOf(null, "real", "", "", "", 921.5, point(-84.0, 34.0)),
            listOf(null, "negative", "", "", "", -282L, point(-84.0, 34.0)),
            listOf(null, "null", "", "", "", null, point(-84.0, 34.0)),
            listOf(null, "text", "", "", "", "921", point(-84.0, 34.0)),
            listOf(null, "blob", "", "", "", byteArrayOf(1), point(-84.0, 34.0)),
        ), "x.lps")
        assertEquals(listOf(921.0, 921.5, -282.0, null, null, null), read.points.map { it.elevationFt })
    }

    @Test
    fun `a point whose coordinates are not numbers is skipped, where the web would hand NaN to its map`() {
        val read = LpsReader.read(lps(
            listOf(null, "nan", "", "", "", 0L, point(Double.NaN, 34.0)),
            listOf(null, "inf", "", "", "", 0L, point(-84.0, Double.POSITIVE_INFINITY)),
            listOf(null, "ok", "", "", "", 0L, point(-84.0, 34.0)),
        ), "x.lps")
        assertEquals(listOf("ok"), read.points.map { it.name })
    }

    @Test
    fun `a geometry that is not a point, in either byte order, is skipped`() {
        val read = LpsReader.read(lps(
            listOf(null, "line", "", "", "", 0L, point(-84.0, 34.0, geometryClass = 2)),
            listOf(null, "big", "", "", "", 0L, point(-84.0, 34.0, ByteOrder.BIG_ENDIAN)),
            listOf(null, "bigline", "", "", "", 0L, point(-84.0, 34.0, ByteOrder.BIG_ENDIAN, geometryClass = 2)),
            listOf(null, "text", "", "", "", 0L, "not a blob"),
        ), "x.lps")
        assertEquals(listOf("big"), read.points.map { it.name })
    }

    @Test
    fun `a name that is a number shows as the number, where the web would stop with an error`() {
        val read = LpsReader.read(lps(
            listOf(null, 42L, "", "", "", 0L, point(-84.0, 34.0)),
            listOf(null, 3.5, "", "", "", 0L, point(-84.0, 34.0)),
            listOf(null, 0L, "", "", "", 0L, point(-84.0, 34.0)),                 // zero is falsy there: no name
        ), "x.lps")
        assertEquals(listOf("42", "3.5", ""), read.points.map { it.name })
    }

    @Test
    fun `a first column that is NULL is NULL unless it is the row id`() {
        // The web fills a NULL first column with the row id whatever it is; SQLite does that only for INTEGER PRIMARY KEY.
        val sql = "CREATE TABLE T (A TEXT, B TEXT)"
        val table = SqliteReader(MiniSqlite.build(MiniSqlite.Table("T", sql, rows = listOf(listOf(null, "b"))))).readTable("T")!!
        assertNull(table.rows.single()[0])
        val aliased = SqliteReader(MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A INTEGER PRIMARY KEY, B TEXT)", rows = listOf(listOf(null, "b")), rowIds = listOf(77)))).readTable("T")!!
        assertEquals(77L, aliased.rows.single()[0])
    }

    @Test
    fun `a points table of the wrong sort is refused with the web's message`() {
        val noCoordinate = lps(listOf(null, "a", "", "", "", 0L, null), sql = "CREATE TABLE Points (Pedigree INTEGER PRIMARY KEY, ID TEXT)")
        assertEquals("This .LPS file contains no readable points.", assertThrows<FormatException> { LpsReader.read(noCoordinate, "x.lps") }.message)
    }

    // -- .ths ---------------------------------------------------------------------------

    private val threatsSql = "CREATE TABLE THREATS (ID LONG, MILSTD_ID TEXT(15), LATITUDE_DEG DOUBLE, LONGITUDE_DEG DOUBLE, OFFICIAL_NAME TEXT(50), INFORMATION TEXT(255), SOURCE TEXT(32))"
    private val radarSql = "CREATE TABLE THREATRADAR (ID LONG, RADAR_TYPE TINYINT, SHOW_MASK INTEGER, SHOW_RANGE_RINGS INTEGER, RANGE_NMI DOUBLE, ANTENNAE_HEIGHT_FT DOUBLE, AGL_NOT_MSL INTEGER, " +
        "ELEVATION1 LONG, ELEVATION2 LONG, ELEVATION3 LONG, COLOR1 LONG, COLOR2 LONG, COLOR3 LONG, MASK1_VIEWABLE INTEGER, MASK2_VIEWABLE INTEGER, MASK3_VIEWABLE INTEGER)"

    private fun ths(threats: List<List<Any?>>, radars: List<List<Any?>>? = null, tsql: String = threatsSql): ByteArray {
        val tables = mutableListOf(MiniSqlite.Table("THREATS", tsql, rows = threats))
        if (radars != null) tables.add(MiniSqlite.Table("THREATRADAR", radarSql, rows = radars))
        return MiniSqlite.build(*tables.toTypedArray())
    }

    @Test
    fun `a threat with no position is skipped, where the web puts it at 0, 0`() {
        val read = ThsReader.read(ths(listOf(
            listOf(1L, "S", null, -84.0, "no lat", "", ""),
            listOf(2L, "S", 34.0, null, "no lon", "", ""),
            listOf(3L, "S", "n/a", -84.0, "text lat", "", ""),
            listOf(4L, "S", 34.0, -84.0, "fine", "", ""),
        )))
        assertEquals(listOf("fine"), read.map { it.name })
    }

    @Test
    fun `a position written as text still reads`() {
        val read = ThsReader.read(ths(listOf(listOf(1L, "S", " 34.5 ", "-84.25", "text", "", ""))))
        assertEquals(34.5 to -84.25, read.single().lat to read.single().lon)
    }

    @Test
    fun `a threat with no radar rows, or no radar table at all, gets the two default radars`() {
        for (radars in listOf<List<List<Any?>>?>(null, emptyList())) {
            val read = ThsReader.read(ths(listOf(listOf(1L, "S", 34.0, -84.0, "x", "", "")), radars))
            assertEquals(Radars.defaultPair(), read.single().radars)
        }
    }

    @Test
    fun `text defaults apply to empty and missing values, and blank-but-present text is trimmed to nothing`() {
        val read = ThsReader.read(ths(listOf(
            listOf(1L, null, 34.0, -84.0, null, null, null),
            listOf(2L, "", 34.0, -84.0, "", "", ""),
            listOf(3L, "  ", 35.0, -85.0, "  ", "  info  ", "  "),
        )))
        assertEquals(listOf("Threat", "Threat", ""), read.map { it.name })
        assertEquals(listOf("SHGPEWMAI------", "SHGPEWMAI------", ""), read.map { it.milstdId })
        assertEquals(listOf("SOF", "SOF", ""), read.map { it.source })
        assertEquals(listOf("", "", "info"), read.map { it.information })
    }

    @Test
    fun `radars join their threat by id, whether SQLite kept the id as an integer or a real`() {
        val read = ThsReader.read(ths(
            threats = listOf(listOf(1.0, "S", 34.0, -84.0, "real id", "", ""), listOf(2L, "S", 35.0, -85.0, "int id", "", "")),
            radars = listOf(
                listOf(1L, 0L, 1L, 1L, 30.0, 10.0, 1L, 100L, 200L, 300L, 7L, 8L, 9L, 1L, 0L, 1L),
                listOf(2.0, 1L, 1L, 1L, 12.0, 10.0, 1L, 100L, 200L, 300L, 7L, 8L, 9L, 1L, 0L, 1L),
                listOf(99L, 1L, 1L, 1L, 12.0, 10.0, 1L, 100L, 200L, 300L, 7L, 8L, 9L, 1L, 0L, 1L),           // belongs to nobody
            ),
        ))
        assertEquals(listOf(30.0), read[0].radars.map { it.rangeNmi })
        assertEquals(listOf(12.0), read[1].radars.map { it.rangeNmi })
    }

    @Test
    fun `a radar row with NULLs takes the type's defaults, and flags follow the web`() {
        val row = listOf(1L, 1L, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        val r = ThsReader.read(ths(listOf(listOf(1L, "S", 34.0, -84.0, "x", "", "")), listOf(row))).single().radars.single()
        val template = Radars.default(1)
        assertEquals(template.copy(aglNotMsl = false), r)                 // AGL_NOT_MSL defaults to false, the rest to true
    }

    @Test
    fun `values the radar row does give win, and a type other than detection is an engagement radar that keeps its number`() {
        val row = listOf(1L, 2L, 0L, 1L, 8.5, 33.0, 1L, 120L, 250L, 480L, 11L, 12L, 13L, 0L, 1L, 2L)
        val r = ThsReader.read(ths(listOf(listOf(1L, "S", 34.0, -84.0, "x", "", "")), listOf(row))).single().radars.single()
        assertEquals(2, r.type)
        assertEquals(listOf(false, true, true), listOf(r.showMask, r.showRangeRings, r.aglNotMsl))
        assertEquals(listOf(8.5, 33.0), listOf(r.rangeNmi, r.antennaHeightFt))
        assertEquals(listOf(120.0, 250.0, 480.0), r.bands.map { it.altFt })
        assertEquals(listOf(11, 12, 13), r.bands.map { it.colorIndex })
        assertEquals(listOf(false, true, true), r.bands.map { it.viewable })
        // The colours are the engagement defaults: a .ths holds AMPS's colour number, not a colour.
        assertEquals(Radars.default(1).bands.map { it.color to it.alpha }, r.bands.map { it.color to it.alpha })
    }

    @Test
    fun `a radar value that is not a number takes the default, where the web would carry NaN`() {
        val row = listOf(1L, 0L, 1L, 1L, "far", "tall", 1L, "low", 250L, 500L, "red", 3L, 5L, 1L, 1L, 1L)
        val r = ThsReader.read(ths(listOf(listOf(1L, "S", 34.0, -84.0, "x", "", "")), listOf(row))).single().radars.single()
        val template = Radars.default(0)
        assertEquals(template.rangeNmi, r.rangeNmi)
        assertEquals(template.antennaHeightFt, r.antennaHeightFt)
        assertEquals(template.bands[0].altFt, r.bands[0].altFt)
        assertEquals(template.bands[0].colorIndex, r.bands[0].colorIndex)
    }

    @Test
    fun `a table list with no THREATS table says so with the web's message`() {
        val bytes = MiniSqlite.build(MiniSqlite.Table("OTHER", "CREATE TABLE OTHER (A)"))
        assertEquals("No THREATS table found — is this an AMPS .ths file?", assertThrows<FormatException> { ThsReader.read(bytes) }.message)
    }
}
