package app.ezpztac.formats

import java.util.Locale

/** What a file somebody sent to the app is. */
public enum class FileKind {
    /** An AMPS `.LPS`: a SQLite database with a `Points` table. */
    LOCAL_POINTS,

    /** An AMPS `.ths`: a SQLite database with a `THREATS` table. */
    THREATS,

    /** An AMPS `.msnx`: a zip of XML documents. */
    MISSION,

    /** Not something the app opens. */
    UNKNOWN,
}

/**
 * Tells what a file is by what is in it, falling back to what it is called. An `.LPS` and a `.ths` have no media type of their own and
 * arrive from other apps as `application/octet-stream`, so the name cannot be trusted and the content decides: a SQLite database is
 * asked for its tables, a zip is a mission. Only when the content does not say (a file cut short, a database with neither table) does
 * the extension choose, so the reader for that kind refuses it in its own words ("This doesn't look like an AMPS .ths threat file")
 * rather than the app saying only "unknown".
 *
 * The file comes from whoever sent it, so this reads nothing but the header and the schema, and a damaged file is [FileKind.UNKNOWN]
 * (or the kind its name claims) and never an exception.
 */
public object FileKinds {
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    public fun detect(name: String?, bytes: ByteArray): FileKind {
        val hinted = byName(name)
        if (startsWith(bytes, ZIP_MAGIC)) return FileKind.MISSION
        if (startsWith(bytes, SQLITE_MAGIC)) {
            val reader = try {
                SqliteReader(bytes)
            } catch (_: SqliteException) {
                return hinted ?: FileKind.UNKNOWN
            }
            val threats = reader.safeHasTable("THREATS")
            val points = reader.safeHasTable("Points")
            return when {
                threats && points -> hinted?.takeIf { it == FileKind.THREATS || it == FileKind.LOCAL_POINTS } ?: FileKind.THREATS
                threats -> FileKind.THREATS
                points -> FileKind.LOCAL_POINTS
                else -> hinted ?: FileKind.UNKNOWN
            }
        }
        return hinted ?: FileKind.UNKNOWN
    }

    /** What the name claims, or null. Only the extension counts; the same file may arrive as `Points.LPS` or `points.lps`. */
    public fun byName(name: String?): FileKind? {
        val lower = name?.lowercase(Locale.ROOT) ?: return null
        return when {
            lower.endsWith(".lps") -> FileKind.LOCAL_POINTS
            lower.endsWith(".ths") -> FileKind.THREATS
            lower.endsWith(".msnx") -> FileKind.MISSION
            else -> null
        }
    }

    private fun SqliteReader.safeHasTable(table: String): Boolean = try {
        hasTable(table)
    } catch (_: SqliteException) {
        false                                                  // a schema that will not read is no table, not a crash
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }
}
