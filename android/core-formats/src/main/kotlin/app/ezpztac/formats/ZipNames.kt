package app.ezpztac.formats

/**
 * The names of a zip's entries, read from its central directory and nothing else, so that an Office file can be told from a mission
 * (`FileKinds.documentType`). The zip is whoever's it is: no entry is inflated or even reached (`ZipInputStream` would inflate every
 * entry it passes over to find the next), every offset is checked against the bytes, and anything wrong is null, never an exception.
 *
 * Read as the server reads it (`documents/core.py`, over Python's `zipfile`), so the device and the server agree on what is an Office
 * zip: the end record is the last one in the zip's last 64 KB and must be whole; Zip64's counts are taken from its own end record, which
 * the locator just before names; the directory is taken to end where the end records begin, however the offsets say otherwise (as
 * Python and JSZip both allow bytes before a zip); it is walked record by record until its size is used up; and past the server's own
 * limits (20,000 entries, a 2 MB directory) a zip is nothing the server takes. A name is compared byte for byte (as Latin-1), so a
 * non-ASCII name never equals an Office part's, however its bytes would decode.
 */
internal object ZipNames {
    // documents/core.py's _MAX_ZIP_ENTRIES and _MAX_ZIP_DIRECTORY: past them the server calls the file unreadable.
    private const val MAX_ENTRIES = 20_000
    private const val MAX_DIRECTORY = 2L * 1024 * 1024

    private const val END = 0x06054b50L
    private const val END_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val ZIP64_LOCATOR = 0x07064b50L
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val ZIP64_END = 0x06064b50L
    private const val ZIP64_END_SIZE = 56
    private const val CENTRAL = 0x02014b50L
    private const val CENTRAL_SIZE = 46

    /** The entries' names, or null where the directory cannot be read or is past the server's limits. */
    fun read(bytes: ByteArray): Set<String>? = try {
        names(bytes)
    } catch (_: RuntimeException) {
        null                                                   // a backstop: every read below is checked first
    }

    private fun names(bytes: ByteArray): Set<String>? {
        val at = lastEnd(bytes)
        if (at < 0 || bytes.size < at + END_SIZE) return null
        var entries = u16(bytes, at + 10).toLong()
        var size = u32(bytes, at + 12)
        var directoryEnd = at.toLong()
        if (entries == 0xFFFFL || size == 0xFFFFFFFFL) {
            // Zip64: the counts are in its own end record, which the locator just before this one names.
            val locator = at - ZIP64_LOCATOR_SIZE
            if (locator < 0 || u32(bytes, locator) != ZIP64_LOCATOR) return null
            val offset = u64(bytes, locator + 8)
            if (offset < 0 || offset + ZIP64_END_SIZE > bytes.size || u32(bytes, offset.toInt()) != ZIP64_END) return null
            entries = u64(bytes, offset.toInt() + 32)
            size = u64(bytes, offset.toInt() + 40)
            directoryEnd = (locator - ZIP64_END_SIZE).toLong()
        }
        if (entries !in 0L..MAX_ENTRIES.toLong() || size !in 0L..MAX_DIRECTORY) return null
        val start = directoryEnd - size
        if (start < 0) return null
        val names = HashSet<String>()
        var count = 0
        var used = 0L
        while (used < size) {
            val record = (start + used).toInt()
            if (record + CENTRAL_SIZE > bytes.size || u32(bytes, record) != CENTRAL) return null
            val nameLength = u16(bytes, record + 28)
            val extraLength = u16(bytes, record + 30)
            val commentLength = u16(bytes, record + 32)
            if (record + CENTRAL_SIZE + nameLength > bytes.size) return null
            if (++count > MAX_ENTRIES) return null
            names += String(bytes, record + CENTRAL_SIZE, nameLength, Charsets.ISO_8859_1)
            used += CENTRAL_SIZE + nameLength + extraLength + commentLength
        }
        return names
    }

    /** Where the last end record's signature starts within the zip's last 64 KB (Python's `rfind` from there), or -1. */
    private fun lastEnd(bytes: ByteArray): Int {
        val from = maxOf(0, bytes.size - END_SIZE - MAX_COMMENT)
        for (at in bytes.size - 4 downTo from) if (u32(bytes, at) == END) return at
        return -1
    }

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    /** A little-endian 64-bit number; one past a Long's range comes out negative, which every caller refuses. */
    private fun u64(bytes: ByteArray, at: Int): Long = u32(bytes, at) or (u32(bytes, at + 4) shl 32)
}
