package app.ezpztac.data

/**
 * Reading a value longer than a cursor window. Room reads every row of a result through a window of 2 MB on a device, and a row that does
 * not fit cannot be read at all (`SQLiteBlobTooBigException`; Robolectric's native SQLite enforces it too). A mission-pack item may be 5 MB,
 * a library document as large, and a mission's file larger. So a query reads such a column only when it is short ([TEXT_PART] characters,
 * [BYTES_PART] bytes), with its length beside it, and a longer one is read in parts with SQLite's `substr` and put back together, inside the
 * same transaction so the parts are of one version.
 */
internal object LongValues {
    /** Characters of text read at a time: at most 1 MB in UTF-8 even if every one takes four bytes. SQLite counts a text's characters (code points), so a part never splits one. */
    const val TEXT_PART: Int = 256 * 1024

    /** Bytes of a blob read at a time. SQLite counts a blob's bytes. */
    const val BYTES_PART: Int = 1024 * 1024

    /** The text of [size] characters, read [TEXT_PART] at a time by [part] (from character `from`, counted from 1). */
    suspend fun text(size: Int, part: suspend (from: Int, count: Int) -> String?): String {
        val whole = StringBuilder(size)
        var from = 1
        while (from <= size) {
            whole.append(checkNotNull(part(from, TEXT_PART)) { "a value went while it was read" })
            from += TEXT_PART
        }
        return whole.toString()
    }

    /** The blob of [size] bytes, read [BYTES_PART] at a time by [part] (from byte `from`, counted from 1). */
    suspend fun bytes(size: Int, part: suspend (from: Int, count: Int) -> ByteArray?): ByteArray {
        val whole = ByteArray(size)
        var from = 1
        while (from <= size) {
            val read = checkNotNull(part(from, BYTES_PART)) { "a value went while it was read" }
            read.copyInto(whole, from - 1)
            from += BYTES_PART
        }
        return whole
    }
}
