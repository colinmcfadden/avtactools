package app.ezpztac.formats

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Builds tiny SQLite databases in memory, for tests that need a value or a fault no real file has: a threat
 * with no position, a row whose header lies about its size. Each table must fit on one page, and a table with
 * a [Table.rawCells] list carries exactly the cell bytes it is given, however wrong they are.
 *
 * It is test support and is itself only as good as its output, which is why the real files in
 * `contracts/fixtures/sqlite` (written by SQLite) are what the reader is held to. Its output is read back
 * by the reader and by SQLite itself (checked when it was written; see [MiniSqliteTest]).
 */
internal object MiniSqlite {
    class Table(
        val name: String,
        val sql: String,
        val rows: List<List<Any?>> = emptyList(),
        /** Row ids for [rows]; 1, 2, 3 … if left out. */
        val rowIds: List<Long>? = null,
        /** Whole cells, written as they are. Takes the place of [rows]. */
        val rawCells: List<ByteArray>? = null,
        /** What the table list says the root page is, if it should say something untrue. */
        val rootPageOverride: Long? = null,
    )

    const val UTF8 = 1
    const val UTF16LE = 2
    const val UTF16BE = 3

    fun build(vararg tables: Table, pageSize: Int = 4096, encoding: Int = UTF8): ByteArray {
        val charset = when (encoding) { UTF16LE -> Charsets.UTF_16LE; UTF16BE -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val master = tables.mapIndexed { i, t ->
            val root = t.rootPageOverride ?: (i + 2).toLong()
            cell(i + 1L, record(listOf("table", t.name, t.name, root, t.sql), charset))
        }
        val pages = ArrayList<ByteArray>()
        pages.add(leafPage(master, pageSize, headerOffset = 100))
        for (t in tables) {
            val cells = t.rawCells ?: t.rows.mapIndexed { i, row -> cell(t.rowIds?.get(i) ?: (i + 1L), record(row, charset)) }
            pages.add(leafPage(cells, pageSize, headerOffset = 0))
        }
        val file = ByteArray(pageSize * pages.size)
        pages.forEachIndexed { i, page -> System.arraycopy(page, 0, file, i * pageSize, pageSize) }
        header(file, pageSize, pages.size, encoding)
        return file
    }

    /** A table cell: payload size, row id, then the record. */
    fun cell(rowId: Long, record: ByteArray): ByteArray = varint(record.size.toLong()) + varint(rowId) + record

    /** A record: a header of serial types, then the values. */
    fun record(values: List<Any?>, charset: Charset = Charsets.UTF_8): ByteArray {
        val types = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (value in values) {
            when (value) {
                null -> types.write(varint(0))
                is Long -> integer(value, types, body)
                is Int -> integer(value.toLong(), types, body)
                is Double -> { types.write(varint(7)); body.write(java.nio.ByteBuffer.allocate(8).putDouble(value).array()) }
                is String -> { val b = value.toByteArray(charset); types.write(varint(13L + 2 * b.size)); body.write(b) }
                is ByteArray -> { types.write(varint(12L + 2 * value.size)); body.write(value) }
                else -> error("cannot store $value")
            }
        }
        val typeBytes = types.toByteArray()
        return varint(typeBytes.size + 1L) + typeBytes + body.toByteArray()
    }

    private fun integer(value: Long, types: ByteArrayOutputStream, body: ByteArrayOutputStream) {
        val (type, width) = when {
            value == 0L -> 8L to 0
            value == 1L -> 9L to 0
            value in -128..127 -> 1L to 1
            value in -32768..32767 -> 2L to 2
            value in -8388608..8388607 -> 3L to 3
            value in -2147483648..2147483647 -> 4L to 4
            value in -140737488355328..140737488355327 -> 5L to 6
            else -> 6L to 8
        }
        types.write(varint(type))
        for (i in width - 1 downTo 0) body.write((value shr (8 * i)).toInt() and 0xFF)
    }

    /** SQLite's variable-length integer: 7 bits per byte, high bit set on all but the last, and a full 9th byte. */
    fun varint(value: Long): ByteArray {
        if (value ushr 56 != 0L) {
            val out = ByteArray(9)
            out[8] = (value and 0xFF).toByte()
            var rest = value ushr 8
            for (i in 7 downTo 0) { out[i] = ((rest and 0x7F) or 0x80).toByte(); rest = rest ushr 7 }
            return out
        }
        val groups = ArrayList<Int>()
        var rest = value
        do { groups.add((rest and 0x7F).toInt()); rest = rest ushr 7 } while (rest != 0L)
        return ByteArray(groups.size) { i ->
            val group = groups[groups.size - 1 - i]
            (if (i < groups.size - 1) group or 0x80 else group).toByte()
        }
    }

    private fun leafPage(cells: List<ByteArray>, pageSize: Int, headerOffset: Int): ByteArray {
        val page = ByteArray(pageSize)
        val arrayEnd = headerOffset + 8 + 2 * cells.size
        var content = pageSize
        val offsets = ArrayList<Int>()
        for (cell in cells) {
            content -= cell.size
            check(content >= arrayEnd) { "the cells do not fit on one page" }
            System.arraycopy(cell, 0, page, content, cell.size)
            offsets.add(content)
        }
        page[headerOffset] = 13
        put16(page, headerOffset + 3, cells.size)
        put16(page, headerOffset + 5, if (cells.isEmpty()) pageSize else content)
        offsets.forEachIndexed { i, offset -> put16(page, headerOffset + 8 + 2 * i, offset) }
        return page
    }

    private fun header(file: ByteArray, pageSize: Int, pages: Int, encoding: Int) {
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(magic, 0, file, 0, magic.size)
        put16(file, 16, if (pageSize == 65536) 1 else pageSize)
        file[18] = 1; file[19] = 1                        // rollback journal, not WAL
        file[21] = 64; file[22] = 32; file[23] = 32
        put32(file, 24, 1)                                // file change counter
        put32(file, 28, pages)
        put32(file, 40, 1)                                // schema cookie
        put32(file, 44, 4)                                // schema format
        put32(file, 56, encoding)
        put32(file, 92, 1)                                // version-valid-for
        put32(file, 96, 3_045_000)                        // SQLite version number
    }

    fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value shr 8).toByte(); bytes[at + 1] = value.toByte()
    }

    fun put32(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value shr 24).toByte(); bytes[at + 1] = (value shr 16).toByte()
        bytes[at + 2] = (value shr 8).toByte(); bytes[at + 3] = value.toByte()
    }
}
