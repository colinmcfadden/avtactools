package app.ezpztac.formats

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

/** The test builder's own output: read back by the reader, and (when asked) written out for SQLite to check. */
class MiniSqliteTest {
    private val table = MiniSqlite.Table(
        name = "T",
        sql = "CREATE TABLE T (ID INTEGER PRIMARY KEY, N, S TEXT, B BLOB, R REAL)",
        rows = listOf(
            listOf(null, 0L, "zero", byteArrayOf(), 0.5),
            listOf(null, 1L, "one", byteArrayOf(1, 2, 3), -1.25),
            listOf(null, -1L, "ÀÉ 😀", byteArrayOf(0), 1e300),
            listOf(null, 300L, "", null, null),
            listOf(null, -70000L, "x".repeat(200), null, 5e-324),
            listOf(null, 1L shl 40, "", null, null),
            listOf(null, Long.MIN_VALUE, "", null, null),
            listOf(null, Long.MAX_VALUE, "", null, null),
        ),
        rowIds = listOf(5, 6, 7, 8, 9, 10, 11, 12),
    )

    @Test
    fun `what it builds reads back as it was written`() {
        val read = SqliteReader(MiniSqlite.build(table)).readTable("T")!!
        assertEquals(listOf("ID", "N", "S", "B", "R"), read.columns)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L), read.rows.map { it[0] })              // the rowid alias
        assertEquals(listOf(0L, 1L, -1L, 300L, -70000L, 1L shl 40, Long.MIN_VALUE, Long.MAX_VALUE), read.rows.map { it[1] })
        assertEquals(listOf("zero", "one", "ÀÉ 😀", "", "x".repeat(200), "", "", ""), read.rows.map { it[2] })
        assertArrayEquals(byteArrayOf(1, 2, 3), read.rows[1][3] as ByteArray)
        assertNull(read.rows[3][3])
        assertEquals(listOf(0.5, -1.25, 1e300), read.rows.take(3).map { it[4] })
        assertEquals(5e-324, read.rows[4][4])
    }

    @Test
    fun `it writes in either UTF-16 order too`() {
        for (encoding in listOf(MiniSqlite.UTF16LE, MiniSqlite.UTF16BE)) {
            val read = SqliteReader(MiniSqlite.build(table, encoding = encoding)).readTable("T")!!
            assertEquals("ÀÉ 😀", read.rows[2][2], "encoding $encoding")
        }
    }

    /** Set `MINISQLITE_DUMP` to a folder to write the databases out, and open them with the `sqlite3` tool. */
    @Test
    fun `it can write a file for SQLite to check`() {
        val folder = System.getenv("MINISQLITE_DUMP") ?: return
        File(folder, "mini.db").writeBytes(MiniSqlite.build(table))
        File(folder, "mini16.db").writeBytes(MiniSqlite.build(table, encoding = MiniSqlite.UTF16LE))
    }
}
