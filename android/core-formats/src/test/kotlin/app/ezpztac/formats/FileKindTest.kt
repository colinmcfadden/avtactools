package app.ezpztac.formats

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What a file sent to the app is: by its content first, by its name only when the content does not say. */
class FileKindTest {
    private val threats = Fixtures.bytes("sqlite/threats.ths")
    private val points = Fixtures.bytes("sqlite/local-points.lps")
    private val mission = Fixtures.bytes("msnx/sketch-export.msnx")
    private val noTables = Fixtures.bytes("sqlite/values.db")

    @Test
    fun `a threat file, a points file and a mission are told by what is in them`() {
        assertEquals(FileKind.THREATS, FileKinds.detect("anything", threats))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("anything", points))
        assertEquals(FileKind.MISSION, FileKinds.detect("anything", mission))
    }

    @Test
    fun `the content outranks a name that says otherwise`() {
        assertEquals(FileKind.THREATS, FileKinds.detect("points.lps", threats))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("threats.ths", points))
        assertEquals(FileKind.MISSION, FileKinds.detect("threats.ths", mission))
    }

    @Test
    fun `an empty threat file is still a threat file`() {
        assertEquals(FileKind.THREATS, FileKinds.detect(null, Fixtures.bytes("sqlite/threats-empty.ths")))
    }

    @Test
    fun `a database with neither table takes the kind its name claims, so the reader can refuse it in words`() {
        assertEquals(FileKind.THREATS, FileKinds.detect("mine.THS", noTables))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("mine.LPS", noTables))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect("mine.db", noTables))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect(null, noTables))
    }

    @Test
    fun `a database with both tables goes by its name, and is a threat file when the name does not say`() {
        val both = MiniSqlite.build(
            MiniSqlite.Table("THREATS", "CREATE TABLE THREATS (ID INTEGER PRIMARY KEY)", rows = emptyList()),
            MiniSqlite.Table("Points", "CREATE TABLE Points (ID TEXT)", rows = emptyList()),
        )
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("x.lps", both))
        assertEquals(FileKind.THREATS, FileKinds.detect("x.ths", both))
        assertEquals(FileKind.THREATS, FileKinds.detect("x", both))
        assertEquals(FileKind.THREATS, FileKinds.detect("x.msnx", both))                 // a name that names neither table does not pick one
    }

    @Test
    fun `table names are matched without regard to case`() {
        val lower = MiniSqlite.build(MiniSqlite.Table("points", "CREATE TABLE points (ID TEXT)", rows = emptyList()))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect(null, lower))
    }

    @Test
    fun `a file that is not a database or a zip is told by its name alone`() {
        val text = "hello".toByteArray()
        assertEquals(FileKind.THREATS, FileKinds.detect("a.ths", text))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("a.lps", text))
        assertEquals(FileKind.MISSION, FileKinds.detect("a.msnx", text))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect("a.txt", text))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect(null, ByteArray(0)))
    }

    @Test
    fun `a file cut short, or damaged inside, is never an exception`() {
        for (cut in listOf(0, 1, 15, 16, 99, 100, 512, threats.size / 2)) {
            val kind = FileKinds.detect("x.ths", threats.copyOf(cut))
            assertEquals(FileKind.THREATS, kind, "cut at $cut")                         // the name is all that is left to go by
        }
        val scrambled = threats.copyOf().also { for (i in 100 until it.size step 7) it[i] = 0x7F }
        FileKinds.detect(null, scrambled)                                               // whatever it says, it says it and does not throw
    }

    @Test
    fun `a zip header is a mission even with no name`() {
        assertEquals(FileKind.MISSION, FileKinds.detect(null, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect(null, byteArrayOf(0x50, 0x4B, 0x05, 0x06)))   // an empty zip's end record is not a mission
    }

    @Test
    fun `only the extension counts for the name`() {
        assertEquals(null, FileKinds.byName("threats.ths.txt"))
        assertEquals(null, FileKinds.byName("ths"))
        assertEquals(FileKind.THREATS, FileKinds.byName("A B.Ths"))
        assertEquals(null, FileKinds.byName(null))
    }
}
