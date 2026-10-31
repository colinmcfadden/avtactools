package app.ezpztac.formats

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Locale

/** A SQLite file that cannot be read: not a database, or damaged, or using a feature this reader leaves out. */
public class SqliteException(message: String, cause: Throwable? = null) : FormatException(message, cause)

/**
 * One table: its column names, and every row as a list of values in column order.
 *
 * A value is `null`, a [Long] (SQLite's integers), a [Double], a [String] or a [ByteArray].
 */
public class SqlTable internal constructor(
    public val columns: List<String>,
    public val rows: List<List<Any?>>,
) {
    private val index: Map<String, Int> = HashMap<String, Int>().also { map ->
        columns.forEachIndexed { i, name -> map.putIfAbsent(name.lowercase(Locale.ROOT), i) }
    }

    /** The position of [name], matched without regard to case as SQL does, or null if the table has no such column. */
    public fun column(name: String): Int? = index[name.lowercase(Locale.ROOT)]

    /** [name]'s value in [row], or null if it is null or the table has no such column. */
    public fun value(row: List<Any?>, name: String): Any? = column(name)?.let { row.getOrNull(it) }
}

/**
 * A read-only SQLite 3 reader: just enough to pull every row of a table out of a database
 * file, which is what `.LPS` local points and `.ths` threats need. A port of the web's
 * `sqliteReader.js`, held to what SQLite itself reads out of the same files
 * (`contracts/fixtures/sqlite`).
 *
 * It reads table b-trees (interior and leaf pages) and follows overflow pages. It leaves out
 * writing, indexes, `WITHOUT ROWID` tables, virtual tables and write-ahead-log sidecars: none
 * of those carry what these files hold. Text is read in the database's own encoding (UTF-8,
 * or UTF-16 either way).
 *
 * The file comes from whoever sent it, so nothing in it is trusted: every page number, cell
 * offset and length is checked against the file, a page may be reached only once (a b-tree
 * that loops back on itself is refused rather than followed forever), and a damaged file
 * ends in a [SqliteException], never an out-of-range crash or an endless walk.
 *
 * Not thread safe, and the whole file is held in memory: the caller decides how large a file
 * it will read.
 */
public class SqliteReader(private val data: ByteArray) {
    private val pageSize: Int
    private val usableSize: Int
    private val pageCount: Int
    private val charset: Charset

    init {
        if (data.size < HEADER_SIZE || !startsWithMagic()) throw SqliteException("This file is not a SQLite database.")
        val rawPageSize = u16(16)
        pageSize = if (rawPageSize == 1) 65536 else rawPageSize
        if (pageSize < 512 || pageSize > 65536 || pageSize and (pageSize - 1) != 0) throw damaged("it has an invalid page size")
        usableSize = pageSize - (data[20].toInt() and 0xFF)
        if (usableSize < 480) throw damaged("it reserves too much of each page")
        // Only whole pages count: a page cut short by a truncated download is not there.
        pageCount = data.size / pageSize
        if (pageCount < 1) throw damaged("it is cut short")
        charset = when (u32(56)) {
            0L, 1L -> Charsets.UTF_8
            2L -> Charsets.UTF_16LE
            3L -> Charsets.UTF_16BE
            else -> throw damaged("it has an unknown text encoding")
        }
    }

    /**
     * Every row of [tableName] (matched without regard to case), or null if the database has no
     * such table. A column that is an alias for the row id (`INTEGER PRIMARY KEY`) is stored as
     * NULL in each row and is read back as the row id, as SQLite itself returns it.
     */
    public fun readTable(tableName: String): SqlTable? {
        val schema = findTable(tableName) ?: return null
        val definition = parseColumns(schema.sql)
        val rows = ArrayList<List<Any?>>()
        walk(schema.rootPage) { rowId, values ->
            val row = ArrayList<Any?>(definition.names.size)
            for (i in definition.names.indices) {
                val value = values.getOrNull(i)
                row.add(if (value == null && i == definition.rowIdColumn) rowId else value)
            }
            rows.add(row)
        }
        return SqlTable(definition.names, rows)
    }

    /** Whether the database has a table called [tableName] (matched without regard to case); no row is read. */
    public fun hasTable(tableName: String): Boolean = findTable(tableName) != null

    // -- The schema ----------------------------------------------------------------

    private class TableSchema(val rootPage: Int, val sql: String)

    private fun findTable(tableName: String): TableSchema? {
        var found: TableSchema? = null
        val wanted = tableName.lowercase(Locale.ROOT)
        walk(1) { _, values ->
            val type = values.getOrNull(0)
            val name = values.getOrNull(1)
            val rootPage = values.getOrNull(3)
            val sql = values.getOrNull(4)
            if (found == null && type == "table" && name is String && name.lowercase(Locale.ROOT) == wanted) {
                if (rootPage !is Long || rootPage < 1 || rootPage > pageCount) {
                    throw damaged("its table list points past the end of the file")
                }
                found = TableSchema(rootPage.toInt(), sql as? String ?: "")
            }
        }
        return found
    }

    // -- Walking a table b-tree ----------------------------------------------------

    private fun walk(rootPage: Int, visit: (rowId: Long, values: List<Any?>) -> Unit) {
        val seen = HashSet<Int>()
        walkPage(rootPage, 0, seen, visit)
    }

    private fun walkPage(page: Int, depth: Int, seen: MutableSet<Int>, visit: (Long, List<Any?>) -> Unit) {
        if (depth > MAX_DEPTH) throw damaged("a table is nested too deeply")
        val start = pageStart(page)
        if (!seen.add(page)) throw damaged("a table loops back on itself")

        // Page 1 hosts the 100-byte file header before its b-tree content.
        val header = start + if (page == 1) HEADER_SIZE else 0
        need(header, 12)
        val type = data[header].toInt() and 0xFF
        val cellCount = u16(header + 3)

        when (type) {
            INTERIOR_TABLE -> {
                val pointers = header + 12
                need(pointers, cellCount * 2)
                val lowest = pointers - start + cellCount * 2
                for (i in 0 until cellCount) {
                    val cell = start + cellOffset(u16(pointers + i * 2), lowest, minimum = 4)
                    walkPage(u32Page(cell), depth + 1, seen, visit)
                }
                walkPage(u32Page(header + 8), depth + 1, seen, visit)
            }
            LEAF_TABLE -> {
                val pointers = header + 8
                need(pointers, cellCount * 2)
                val lowest = pointers - start + cellCount * 2
                for (i in 0 until cellCount) {
                    var at = start + cellOffset(u16(pointers + i * 2), lowest, minimum = 2)
                    val payloadSize = varint(at)
                    at += varintLength(at)
                    val rowId = varint(at)
                    at += varintLength(at)
                    if (payloadSize < 0 || payloadSize > data.size) throw damaged("a row is larger than the file")
                    val local = localPayloadSize(payloadSize.toInt())
                    val payload = readPayload(at, payloadSize.toInt(), local, start + usableSize, seen)
                    visit(rowId, decodeRecord(payload))
                }
            }
            else -> throw damaged("it has an unexpected page type ($type) where a table should be")
        }
    }

    /** Where a cell is on its page, checked: after the cell pointers ([lowest]) and with room for [minimum] bytes. */
    private fun cellOffset(offset: Int, lowest: Int, minimum: Int): Int {
        if (offset < lowest || offset + minimum > usableSize) throw damaged("a row sits outside its page")
        return offset
    }

    /** How much of a table-leaf payload lives on the page itself, per the SQLite file format. */
    private fun localPayloadSize(payloadSize: Int): Int {
        val maxLocal = usableSize - 35
        if (payloadSize <= maxLocal) return payloadSize
        val minLocal = (usableSize - 12) * 32 / 255 - 23
        val k = minLocal + (payloadSize - minLocal) % (usableSize - 4)
        return if (k <= maxLocal) k else minLocal
    }

    /** A row's whole payload, following its chain of overflow pages when it does not fit on the page. */
    private fun readPayload(at: Int, payloadSize: Int, localSize: Int, pageEnd: Int, seen: MutableSet<Int>): ByteArray {
        val onPage = localSize + if (localSize < payloadSize) 4 else 0
        if (at.toLong() + onPage > pageEnd) throw damaged("a row runs past the end of its page")
        need(at, onPage)
        val payload = ByteArray(payloadSize)
        System.arraycopy(data, at, payload, 0, localSize)
        if (localSize >= payloadSize) return payload

        var written = localSize
        var overflow = u32(at + localSize)
        while (written < payloadSize) {
            if (overflow == 0L) throw damaged("a long value ends before it should")
            val page = overflow.toInt().takeIf { overflow in 1..pageCount.toLong() }
                ?: throw damaged("a long value points past the end of the file")
            if (!seen.add(page)) throw damaged("a long value loops back on itself")
            val off = pageStart(page)
            val chunk = minOf(usableSize - 4, payloadSize - written)
            need(off, 4 + chunk)
            System.arraycopy(data, off + 4, payload, written, chunk)
            written += chunk
            overflow = u32(off)
        }
        return payload
    }

    // -- A row ---------------------------------------------------------------------

    private fun decodeRecord(payload: ByteArray): List<Any?> {
        val buffer = Cursor(payload)
        val headerSize = buffer.varint()
        if (headerSize < 1 || headerSize > payload.size) throw damaged("a row's header does not fit in the row")
        val types = ArrayList<Long>()
        while (buffer.position < headerSize) types.add(buffer.varint())
        if (buffer.position != headerSize.toInt()) throw damaged("a row's header is malformed")

        val values = ArrayList<Any?>(types.size)
        var at = headerSize.toInt()
        for (type in types) {
            if (type == 10L || type == 11L || type < 0) throw damaged("a row has a value of an unsupported type ($type)")
            val length = serialLength(type)
            if (length < 0 || at + length > payload.size) throw damaged("a row's value runs past the end of the row")
            values.add(
                when {
                    type == 0L -> null
                    type in 1L..6L -> signedBigEndian(payload, at, length)
                    type == 7L -> java.lang.Double.longBitsToDouble(signedBigEndian(payload, at, 8))
                    type == 8L -> 0L
                    type == 9L -> 1L
                    type >= 12 && type % 2 == 0L -> payload.copyOfRange(at, at + length)
                    else -> String(payload, at, length, charset)
                },
            )
            at += length
        }
        return values
    }

    private fun serialLength(type: Long): Int = when {
        type == 0L || type == 8L || type == 9L -> 0
        type in 1L..4L -> type.toInt()
        type == 5L -> 6
        type == 6L || type == 7L -> 8
        type == 10L || type == 11L -> -1
        else -> ((type - 12) / 2).takeIf { it in 0..data.size.toLong() }?.toInt() ?: -1
    }

    private fun signedBigEndian(bytes: ByteArray, at: Int, length: Int): Long {
        var value = if (bytes[at] < 0) -1L else 0L         // sign-extend, then build the two's-complement number
        for (i in 0 until length) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    // -- Bytes ---------------------------------------------------------------------

    private class Cursor(val bytes: ByteArray) {
        var position = 0
        fun varint(): Long {
            var value = 0L
            for (i in 0 until 8) {
                if (position >= bytes.size) throw SqliteException("This SQLite file is damaged: a row ends in the middle of a number.")
                val byte = bytes[position++].toInt() and 0xFF
                value = (value shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) return value
            }
            if (position >= bytes.size) throw SqliteException("This SQLite file is damaged: a row ends in the middle of a number.")
            return (value shl 8) or (bytes[position++].toLong() and 0xFF)
        }
    }

    private fun varint(at: Int): Long {
        need(at, 1)
        var value = 0L
        for (i in 0 until 8) {
            need(at + i, 1)
            val byte = data[at + i].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) return value
        }
        need(at + 8, 1)
        return (value shl 8) or (data[at + 8].toLong() and 0xFF)
    }

    private fun varintLength(at: Int): Int {
        for (i in 0 until 8) if (data[at + i].toInt() and 0x80 == 0) return i + 1
        return 9
    }

    private fun pageStart(page: Int): Int {
        if (page < 1 || page > pageCount) throw damaged("a table points past the end of the file")
        return (page - 1) * pageSize
    }

    private fun u16(at: Int): Int {
        need(at, 2)
        return ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    }

    private fun u32(at: Int): Long {
        need(at, 4)
        return ByteBuffer.wrap(data, at, 4).int.toLong() and 0xFFFFFFFFL
    }

    private fun u32Page(at: Int): Int {
        val page = u32(at)
        if (page < 1 || page > pageCount) throw damaged("a table points past the end of the file")
        return page.toInt()
    }

    private fun need(at: Int, length: Int) {
        if (at < 0 || length < 0 || at.toLong() + length > data.size) throw damaged("it is cut short")
    }

    private fun startsWithMagic(): Boolean {
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return true
    }

    private fun damaged(why: String) = SqliteException("This SQLite file is damaged: $why.")

    private companion object {
        val MAGIC: ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        const val HEADER_SIZE = 100
        const val INTERIOR_TABLE = 5
        const val LEAF_TABLE = 13

        /** SQLite's own limit is 20 levels; a bit of room costs nothing. */
        const val MAX_DEPTH = 24
    }
}

// -- CREATE TABLE ----------------------------------------------------------------

internal class ColumnDefinitions(val names: List<String>, val rowIdColumn: Int)

/**
 * The column names of a `CREATE TABLE` statement, in order, and which one (if any) is an alias for
 * the row id. Splits the parenthesised body at depth-zero commas, skipping quoted names, string
 * literals and comments, and leaves out table constraints.
 */
internal fun parseColumns(createSql: String): ColumnDefinitions {
    val parts = splitBody(createSql)
    val names = ArrayList<String>()
    var rowIdColumn = -1
    var tablePrimaryKey: String? = null
    val declared = ArrayList<String>()
    for (part in parts) {
        val text = part.trim()
        if (text.isEmpty()) continue
        if (CONSTRAINT.containsMatchIn(text)) {
            Regex("""^(?:CONSTRAINT\s+\S+\s+)?PRIMARY\s+KEY\s*\(\s*(\S+?)\s*\)""", RegexOption.IGNORE_CASE)
                .find(text)?.let { tablePrimaryKey = unquote(it.groupValues[1]) }
            continue
        }
        val (name, rest) = splitName(text)
        names.add(name)
        declared.add(rest)
    }
    declared.forEachIndexed { i, rest ->
        if (ROWID_ALIAS.containsMatchIn(rest) || (tablePrimaryKey.equals(names[i], ignoreCase = true) && INTEGER_TYPE.containsMatchIn(rest))) {
            if (rowIdColumn < 0) rowIdColumn = i
        }
    }
    return ColumnDefinitions(names, rowIdColumn)
}

private val CONSTRAINT = Regex("""^(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT)\b""", RegexOption.IGNORE_CASE)
private val INTEGER_TYPE = Regex("""^\s*INTEGER\b(?!\s*\()""", RegexOption.IGNORE_CASE)
private val ROWID_ALIAS = Regex("""^\s*INTEGER\b(?!\s*\()[^,]*?\bPRIMARY\s+KEY\b(?!\s+DESC\b)""", RegexOption.IGNORE_CASE)

private fun unquote(name: String): String = splitName(name).first

/** The parts of the table's parenthesised body, split at commas that are not inside brackets, quotes or comments. */
private fun splitBody(sql: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var depth = 0
    var i = 0
    var started = false
    while (i < sql.length) {
        val c = sql[i]
        when {
            c == '-' && sql.startsWith("--", i) -> { i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }; continue }
            c == '/' && sql.startsWith("/*", i) -> { i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }; continue }
            c == '\'' || c == '"' || c == '`' || c == '[' -> {
                val close = if (c == '[') ']' else c
                var j = i + 1
                while (j < sql.length) {
                    if (sql[j] == close) {
                        if (close != ']' && j + 1 < sql.length && sql[j + 1] == close) { j += 2; continue }   // a doubled quote is a literal one
                        break
                    }
                    j++
                }
                if (started) current.append(sql, i, minOf(j + 1, sql.length))
                i = j + 1
                continue
            }
            c == '(' -> {
                depth++
                if (depth == 1 && !started) { started = true; i++; continue }
            }
            c == ')' -> {
                depth--
                if (depth == 0 && started) { parts.add(current.toString()); return parts }
            }
            c == ',' && depth == 1 && started -> { parts.add(current.toString()); current.clear(); i++; continue }
        }
        if (started) current.append(c)
        i++
    }
    return if (started) parts + current.toString() else emptyList()
}

/** A column definition's name (unquoted) and the rest of it. */
private fun splitName(definition: String): Pair<String, String> {
    val first = definition[0]
    if (first == '"' || first == '`' || first == '[') {
        val close = if (first == '[') ']' else first
        val name = StringBuilder()
        var j = 1
        while (j < definition.length) {
            if (definition[j] == close) {
                if (close != ']' && j + 1 < definition.length && definition[j + 1] == close) { name.append(close); j += 2; continue }
                break
            }
            name.append(definition[j]); j++
        }
        return name.toString() to definition.substring(minOf(j + 1, definition.length))
    }
    val end = definition.indexOfFirst { it.isWhitespace() }.let { if (it < 0) definition.length else it }
    return definition.substring(0, end) to definition.substring(end)
}
