package app.ezpztac.formats

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.Random

/**
 * A `.LPS` or `.ths` arrives by email or AirDrop, so the reader gets whatever a sender made: a file cut short by a
 * bad download, a flipped byte, or a b-tree built to loop forever. Every one of them must end in a [SqliteException]
 * (or read fine), never a crash with an out-of-range index, a huge allocation or a walk that does not end.
 */
class SqliteHostileFileTest {
    private val points = Fixtures.bytes("sqlite/local-points.lps")
    private val large = Fixtures.bytes("sqlite/local-points-large.lps")
    private val deep = Fixtures.bytes("sqlite/deep-tree.db")
    private val threats = Fixtures.bytes("sqlite/threats.ths")

    private val tablesOf = mapOf(
        "local-points.lps" to listOf("Points"),
        "local-points-large.lps" to listOf("Points"),
        "deep-tree.db" to listOf("Numbers"),
        "threats.ths" to listOf("THREATS", "THREATRADAR", "SYSTEM", "LINKS", "Nothing"),
    )

    private fun fixture(name: String) = Fixtures.bytes("sqlite/$name")

    /** Reads every table of [bytes]. Returns the exception if it ended in one, and fails on any other kind. */
    private fun outcome(bytes: ByteArray, tables: List<String>, what: String): SqliteException? {
        try {
            val reader = SqliteReader(bytes)
            tables.forEach { reader.readTable(it) }
            return null
        } catch (e: SqliteException) {
            return e
        } catch (e: Throwable) {
            fail<Unit>("$what: ${e::class.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun refused(bytes: ByteArray, table: String, why: String): SqliteException =
        assertThrows<SqliteException>(why) { SqliteReader(bytes).readTable(table) }

    /** Refused, and for the reason named: a fault caught only by some later check would hide a missing earlier one. */
    private fun refusedBecause(bytes: ByteArray, table: String, reason: String, why: String) {
        val message = refused(bytes, table, why).message!!
        assertTrue(message.contains(reason), "$why: expected a refusal about \"$reason\", got \"$message\"")
    }

    // -- A file cut short, or with a byte changed ------------------------------------

    @Test
    fun `a file cut anywhere reads or is refused`() {
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            for ((name, tables) in tablesOf) {
                val full = fixture(name)
                val cuts = (0 until full.size step 61).toList() + listOf(99, 100, 101, 511, 512, 513, 4095, 4096, 4097, full.size - 1)
                for (cut in cuts.filter { it < full.size }) outcome(full.copyOf(cut), tables, "$name cut at $cut")
            }
        }
    }

    @Test
    fun `a file with bytes changed reads or is refused`() {
        assertTimeoutPreemptively(Duration.ofSeconds(120)) {
            val random = Random(20261002)
            var refusals = 0
            var total = 0
            for ((name, tables) in tablesOf) {
                val full = fixture(name)
                repeat(1500) { attempt ->
                    val damaged = full.copyOf()
                    // Half the time aim at the page headers, where the structure is, rather than at row data.
                    val pageSize = 1 shl (if (name.startsWith("local-points-large")) 10 else if (name == "deep-tree.db") 9 else 12)
                    repeat(1 + random.nextInt(3)) {
                        val at = if (random.nextBoolean()) random.nextInt(damaged.size)
                                 else (random.nextInt(damaged.size / pageSize) * pageSize + random.nextInt(24)).coerceAtMost(damaged.size - 1)
                        damaged[at] = random.nextInt(256).toByte()
                    }
                    total++
                    if (outcome(damaged, tables, "$name, attempt $attempt") != null) refusals++
                }
            }
            // Corruption in the structure must be noticed; if none ever was, this test is not testing anything.
            assertTrue(refusals > total / 50, "only $refusals of $total damaged files were refused")
        }
    }

    // -- Faults aimed at one check each -----------------------------------------------

    @Test
    fun `a page size that is not a power of two between 512 and 65536 is refused`() {
        for (size in listOf(0, 2, 256, 511, 513, 4097, 65535)) {
            val bytes = points.copyOf()
            MiniSqlite.put16(bytes, 16, size)
            refused(bytes, "Points", "page size $size")
        }
        // 1 stands for 65536, which is real, though this file is not that large: the page count is what refuses it.
        val bytes = points.copyOf(); MiniSqlite.put16(bytes, 16, 1)
        refused(bytes, "Points", "page size 65536 on a short file")
    }

    @Test
    fun `reserved space that leaves a page too small to use is refused`() {
        val small = MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A)", rows = listOf(listOf(1L))), pageSize = 512)
        assertEquals(1L, SqliteReader(small).readTable("T")!!.rows.single().single())
        val bytes = small.copyOf(); bytes[20] = 100                             // 412 usable bytes of 512: less than SQLite allows
        assertTrue(assertThrows<SqliteException> { SqliteReader(bytes) }.message!!.contains("reserves too much"))
    }

    @Test
    fun `a text encoding that does not exist is refused`() {
        val bytes = points.copyOf(); MiniSqlite.put32(bytes, 56, 9)
        refused(bytes, "Points", "encoding 9")
    }

    @Test
    fun `a b-tree that points back at itself is refused, not followed forever`() {
        // The root of deep-tree.db is page 2 (512-byte pages); make its right-most child page 2 again.
        val bytes = deep.copyOf(); MiniSqlite.put32(bytes, 512 + 8, 2)
        assertTrue(refused(bytes, "Numbers", "root's right child is itself").message!!.contains("loops back"))
    }

    @Test
    fun `a child page past the end of the file is refused`() {
        val bytes = deep.copyOf(); MiniSqlite.put32(bytes, 512 + 8, 99999)
        assertTrue(refused(bytes, "Numbers", "right child past the end").message!!.contains("past the end"))
        val zero = deep.copyOf(); MiniSqlite.put32(zero, 512 + 8, 0)
        refused(zero, "Numbers", "right child is page 0")
    }

    @Test
    fun `a page that is neither a table leaf nor a table interior page is refused`() {
        val bytes = points.copyOf(); bytes[4096] = 10                      // an index leaf, where the table should be
        assertTrue(refused(bytes, "Points", "index page").message!!.contains("unexpected page type"))
    }

    /** Two tables, so the first one's page is not the last in the file and a cell running off it reads real bytes. */
    private val twoPages = MiniSqlite.build(
        MiniSqlite.Table("T", "CREATE TABLE T (A, B)", rows = listOf(listOf(1L, "one"), listOf(2L, "two"))),
        MiniSqlite.Table("U", "CREATE TABLE U (A, B)", rows = listOf(listOf(3L, "three"))),
    )

    @Test
    fun `a cell that points outside its page is refused`() {
        for (pointer in listOf(0, 1, 7, 11, 4095, 4096, 65535)) {
            val bytes = twoPages.copyOf(); MiniSqlite.put16(bytes, 4096 + 8, pointer)         // T's first cell
            refusedBecause(bytes, "T", "outside its page", "cell offset $pointer")
        }
    }

    @Test
    fun `a cell count larger than the page can hold is refused`() {
        for (count in listOf(2000, 4000)) {
            val bytes = twoPages.copyOf(); MiniSqlite.put16(bytes, 4096 + 3, count)
            refusedBecause(bytes, "T", "outside its page", "cell count $count")
        }
        val past = twoPages.copyOf(); MiniSqlite.put16(past, 4096 + 3, 65535)
        refusedBecause(past, "T", "cut short", "cell count 65535, pointers past the file")
    }

    @Test
    fun `a row whose stated size runs off the end of its page is refused`() {
        // The cell sits at the very end of the page and claims 3000 bytes, which would read on into the next page.
        val lying = MiniSqlite.varint(3000) + MiniSqlite.varint(1) + byteArrayOf(2, 1, 7)
        val bytes = MiniSqlite.build(
            MiniSqlite.Table("T", "CREATE TABLE T (A, B)", rawCells = listOf(lying)),
            MiniSqlite.Table("U", "CREATE TABLE U (A, B)", rows = listOf(listOf(3L, "x".repeat(3500)))),
        )
        refusedBecause(bytes, "T", "past the end of its page", "3000 bytes claimed at the end of a page")
    }

    @Test
    fun `a file cut inside a table's page does not have that page`() {
        refusedBecause(twoPages.copyOf(twoPages.size - 1), "U", "past the end of the file", "the last page is one byte short")
    }

    // The overflow chain of the long description in local-points-large.lps: pages that are mostly 'x' and start with
    // a page number (a leaf page starts with its type, 13, which is how the leaf holding the value's first part is told apart).
    private fun overflowChain(): List<Int> {
        val size = 1024
        val chain = (1..large.size / size).filter { p ->
            val start = (p - 1) * size
            large[start] == 0.toByte() && (start + 4 until start + size).count { large[it] == 'x'.code.toByte() } > size / 2
        }
        assertTrue(chain.size >= 3, "the fixture's long value spans several overflow pages")
        return chain
    }

    private fun next(bytes: ByteArray, page: Int) =
        ((bytes[(page - 1) * 1024].toInt() and 0xFF) shl 24) or ((bytes[(page - 1) * 1024 + 1].toInt() and 0xFF) shl 16) or
            ((bytes[(page - 1) * 1024 + 2].toInt() and 0xFF) shl 8) or (bytes[(page - 1) * 1024 + 3].toInt() and 0xFF)

    @Test
    fun `an overflow chain that loops, ends early or runs off the file is refused`() {
        val chain = overflowChain()
        val first = chain.first { c -> chain.none { next(large, it) == c } }
        val second = next(large, first)
        val third = next(large, second)

        // (The pointer in the chain's *last* page is never followed, so the loop has to close earlier.)
        val loop = large.copyOf(); MiniSqlite.put32(loop, (third - 1) * 1024, first)
        assertTrue(refused(loop, "Points", "the third overflow page points back at the first").message!!.contains("loops back"))
        val tight = large.copyOf(); MiniSqlite.put32(tight, (second - 1) * 1024, second)
        assertTrue(refused(tight, "Points", "an overflow page points at itself").message!!.contains("loops back"))

        val early = large.copyOf(); MiniSqlite.put32(early, (first - 1) * 1024, 0)
        assertTrue(refused(early, "Points", "chain ends at the first page").message!!.contains("ends before it should"))

        val away = large.copyOf(); MiniSqlite.put32(away, (first - 1) * 1024, 99999)
        assertTrue(refused(away, "Points", "chain points past the end").message!!.contains("past the end"))
    }

    // -- Rows that lie about themselves ----------------------------------------------------

    private fun tableWith(vararg cells: ByteArray) = MiniSqlite.Table("T", "CREATE TABLE T (A, B)", rawCells = cells.toList())
    private fun read(vararg cells: ByteArray) = SqliteReader(MiniSqlite.build(tableWith(*cells))).readTable("T")!!
    private fun readFails(why: String, vararg cells: ByteArray): SqliteException =
        assertThrows<SqliteException>(why) { read(*cells) }

    private fun readFailsBecause(reason: String, why: String, vararg cells: ByteArray) {
        val message = readFails(why, *cells).message!!
        assertTrue(message.contains(reason), "$why: expected a refusal about \"$reason\", got \"$message\"")
    }

    @Test
    fun `a row whose header is bigger than the row is refused`() {
        readFailsBecause("does not fit in the row", "header larger than the payload", MiniSqlite.cell(1, byteArrayOf(50, 1, 1)))
        readFailsBecause("does not fit in the row", "header size zero", MiniSqlite.cell(1, byteArrayOf(0, 1, 1)))
        readFailsBecause("is malformed", "header size runs into its own types", MiniSqlite.cell(1, byteArrayOf(2, 0x81.toByte(), 0x01)))
    }

    @Test
    fun `a row with a value that runs past its end is refused`() {
        // header: size 2, one column of type 20 (a 4-byte blob), but the data is empty
        readFailsBecause("runs past the end of the row", "blob longer than the row", MiniSqlite.cell(1, byteArrayOf(2, 20)))
        // a text of a quintillion bytes
        val huge = MiniSqlite.varint(Long.MAX_VALUE - 1)
        readFailsBecause("runs past the end of the row", "enormous text", MiniSqlite.cell(1, byteArrayOf((huge.size + 1).toByte()) + huge + byteArrayOf(1, 2)))
        readFailsBecause("runs past the end of the row", "int64 cut short", MiniSqlite.cell(1, byteArrayOf(2, 6, 1, 2, 3)))
    }

    @Test
    fun `a row with a reserved or negative serial type is refused`() {
        readFailsBecause("unsupported type (10)", "type 10", MiniSqlite.cell(1, byteArrayOf(2, 10, 0)))
        readFailsBecause("unsupported type (11)", "type 11", MiniSqlite.cell(1, byteArrayOf(2, 11, 0)))
        val negative = MiniSqlite.varint(-5L)                                    // a nine-byte varint that reads as negative
        readFailsBecause("unsupported type", "negative type", MiniSqlite.cell(1, byteArrayOf((negative.size + 1).toByte()) + negative))
    }

    @Test
    fun `a row whose header ends in the middle of a number is refused`() {
        readFailsBecause("middle of a number", "unterminated varint", MiniSqlite.cell(1, byteArrayOf(0x82.toByte(), 0x81.toByte())))
    }

    @Test
    fun `a row that claims to be larger than the whole file is refused without trying to hold it`() {
        // Just under 2 GB: more than any phone will give an app in one array, and far more than the file holds.
        val claims = MiniSqlite.varint(2_100_000_000L) + MiniSqlite.varint(1) + byteArrayOf(2, 1, 7)
        readFailsBecause("larger than the file", "2 GB payload in a 12 KB file", claims)
        val negative = MiniSqlite.varint(-1L) + MiniSqlite.varint(1) + byteArrayOf(2, 1, 7)
        readFailsBecause("larger than the file", "negative payload size", negative)
    }

    @Test
    fun `a table list that names a root page that is not there is refused`() {
        for (root in listOf(0L, -1L, 99L, 4_294_967_296L)) {
            val bytes = MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A)", rows = listOf(listOf(1L)), rootPageOverride = root))
            assertThrows<SqliteException>("root $root") { SqliteReader(bytes).readTable("T") }
        }
        // ... but only for the table asked for: another's bad root does not matter.
        val bytes = MiniSqlite.build(
            MiniSqlite.Table("Bad", "CREATE TABLE Bad (A)", rootPageOverride = 0),
            MiniSqlite.Table("Good", "CREATE TABLE Good (A)", rows = listOf(listOf(7L))),
        )
        assertEquals(7L, SqliteReader(bytes).readTable("Good")!!.rows.single().single())
    }

    @Test
    fun `a table that is WITHOUT ROWID, or an index, is refused rather than misread`() {
        // An index b-tree page has type 2 or 10; the reader only knows tables.
        val bytes = MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A PRIMARY KEY) WITHOUT ROWID", rows = listOf(listOf(1L))))
        bytes[4096 + 4096 * 0 + 0] = bytes[4096]                                   // (page 2 is the table; keep its type)
        bytes[4096] = 10
        assertThrows<SqliteException> { SqliteReader(bytes).readTable("T") }
    }

    @Test
    fun `a b-tree nested far deeper than SQLite allows is refused, not recursed into until the stack gives out`() {
        // Not a loop: 300 interior pages, each the right-most child of the one before, ending in an empty leaf.
        val pageSize = 512
        val depth = 300
        val file = ByteArray(pageSize * (depth + 2))
        val master = MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A)", rows = listOf(listOf(1L))), pageSize = pageSize)
        System.arraycopy(master, 0, file, 0, pageSize)                                  // page 1: the table list, T at page 2
        MiniSqlite.put32(file, 28, depth + 1)
        for (p in 2..depth + 1) {
            val at = (p - 1) * pageSize
            file[at] = 5                                                                // an interior table page with no cells
            MiniSqlite.put32(file, at + 8, p + 1)                                       // whose right-most child is the next page
        }
        file[(depth + 1) * pageSize] = 13                                               // the last page: an empty leaf
        assertTrue(refused(file, "T", "300 levels").message!!.contains("nested too deeply"))
    }

    @Test
    fun `a leaf that is the whole file and nothing more reads, so the checks are not too tight`() {
        val ok = MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A, B)", rows = listOf(listOf(1L, "a"), listOf(null, "b"))), pageSize = 512)
        assertEquals(2, SqliteReader(ok).readTable("T")!!.rows.size)
        // Page sizes at both ends of the range.
        val big = MiniSqlite.build(MiniSqlite.Table("T", "CREATE TABLE T (A)", rows = listOf(listOf("x".repeat(20000)))), pageSize = 65536)
        assertEquals(20000, (SqliteReader(big).readTable("T")!!.rows.single().single() as String).length)
    }
}
