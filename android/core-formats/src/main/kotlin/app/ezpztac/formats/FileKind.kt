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

    /**
     * A document (docs/DOCUMENTS.md §9.1): a PDF, or a Word, Excel or PowerPoint file the server turns into one. Which of them is
     * [FileKinds.documentType]'s; nothing of it is read on the device but enough to say what it is, since the server checks it.
     */
    DOCUMENT,

    /** Not something the app opens. */
    UNKNOWN,
}

/**
 * Tells what a file is by what is in it, falling back to what it is called. An `.LPS` and a `.ths` have no media type of their own and
 * arrive from other apps as `application/octet-stream`, so the name cannot be trusted and the content decides: a SQLite database is
 * asked for its tables, a zip is a mission (unless it is Office's, below). Only when the content does not say (a file cut short, a
 * database with neither table) does the extension choose, so the reader for that kind refuses it in its own words ("This doesn't look
 * like an AMPS .ths threat file") rather than the app saying only "unknown".
 *
 * A document is told as the web's `detectImport.js` tells it, in its order: an OLE file (a legacy `.doc`, `.xls` or `.ppt`) first; then
 * a zip, which is asked for Office's parts before it is taken for a mission, since a `.docx`, `.xlsx` or `.pptx` is a zip too; then a
 * SQLite database as before; then a PDF, whose header readers allow a little way in. **Its name never makes a file a document**: no
 * reader on the device refuses a document in its own words (the server checks it, after an upload of up to 25 MB), so a file only
 * called `.pdf` is [FileKind.UNKNOWN], as the web says it is not supported, and a zip called `.docx` without Office's parts is a
 * mission, which the mission reader refuses in its words, as the web's does.
 *
 * The file comes from whoever sent it, so this reads nothing but the header, the schema and a zip's directory of names (never an entry
 * itself), and a damaged file is [FileKind.UNKNOWN] (or the kind its name claims) and never an exception.
 */
public object FileKinds {
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
    private val OLE_MAGIC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.ISO_8859_1)

    // A PDF's header may be a little way in: within the first 1 KB, as the web and the server look for it.
    private const val PDF_HEADER_WITHIN = 1024

    // A legacy Office file whose name says which it is; any other is `office`, which the server tells apart by its streams.
    private val OLE_EXTENSIONS = setOf("doc", "xls", "ppt")

    /** A document's types, as the web's `docType` names them (the server's kinds, and `office` for a legacy file its name does not settle). */
    public val DOCUMENT_TYPES: List<String> = listOf("pdf", "docx", "xlsx", "pptx", "doc", "xls", "ppt", "office")

    public fun detect(name: String?, bytes: ByteArray): FileKind {
        val hinted = byName(name)
        if (documentType(name, bytes) != null) return FileKind.DOCUMENT
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

    /**
     * Which document [bytes] are, or null when they are none: `pdf`; `docx`, `xlsx` or `pptx` for a zip holding `[Content_Types].xml`
     * and Word's, Excel's or PowerPoint's main part; `doc`, `xls` or `ppt` for an OLE file whose [name] ends so, else `office`. A zip
     * that is not Office's (a mission) and a SQLite database are none, whatever their first kilobyte holds, as on the web.
     */
    public fun documentType(name: String?, bytes: ByteArray): String? {
        if (startsWith(bytes, OLE_MAGIC)) return extensionOf(name).takeIf { it in OLE_EXTENSIONS } ?: "office"
        if (startsWith(bytes, ZIP_MAGIC)) return ZipNames.read(bytes)?.let(::officeTypeOf)
        if (startsWith(bytes, SQLITE_MAGIC)) return null
        return if (contains(bytes, PDF_MAGIC, PDF_HEADER_WITHIN)) "pdf" else null
    }

    /** An Office zip's type from the names of its parts, or null (the web's `officeKindOf`, the server's `_ooxml_kind`). */
    private fun officeTypeOf(names: Set<String>): String? = when {
        "[Content_Types].xml" !in names -> null
        "word/document.xml" in names -> "docx"
        "xl/workbook.xml" in names -> "xlsx"
        "ppt/presentation.xml" in names -> "pptx"
        else -> null
    }

    /** What follows a name's last dot, in lower case (the web's `extensionOf`); none for a name without one. */
    private fun extensionOf(name: String?): String {
        val at = name?.lastIndexOf('.') ?: -1
        return if (at < 0) "" else name!!.substring(at + 1).lowercase(Locale.ROOT)
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

    /** Whether [wanted] lies whole within the first [within] bytes. */
    private fun contains(bytes: ByteArray, wanted: ByteArray, within: Int): Boolean {
        val end = minOf(bytes.size, within) - wanted.size
        for (at in 0..end) {
            if ((wanted.indices).all { bytes[at + it] == wanted[it] }) return true
        }
        return false
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) if (bytes[i] != prefix[i]) return false
        return true
    }
}
